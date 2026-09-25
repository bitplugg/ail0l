package com.aiia.app.plugins.engine

import android.content.Context
import android.os.Environment
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import com.aiia.app.plugins.sandbox.PluginSandboxClient
import com.aiia.app.util.ApkIntegrityVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.zip.ZipInputStream

sealed interface InstallState {
    data object Idle : InstallState
    data class AwaitingPermission(val packageFile: File, val manifest: PluginManifest) : InstallState
    data class Installed(val plugin: LoadedPlugin) : InstallState
    data class Failed(val message: String) : InstallState
}

data class LoadedPlugin(
    val manifest: PluginManifest,
    val plugin: AiiaPlugin,
    val source: File,
    val classLoader: ClassLoader? = null
) {
    fun close() = Unit
}

class PluginManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    private val _plugins = MutableStateFlow<List<LoadedPlugin>>(emptyList())
    val state: StateFlow<InstallState> = _state.asStateFlow()
    val plugins: StateFlow<List<LoadedPlugin>> = _plugins.asStateFlow()
    private val installed = mutableMapOf<String, LoadedPlugin>()
    private val sandbox = PluginSandboxClient(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun developmentDirectory(): File = File(
        Environment.getExternalStorageDirectory(),
        "AIIA/plugins"
    ).also { if (!it.exists()) it.mkdirs() }

    suspend fun install(
        file: File,
        expectedSha256: String? = null,
        expectedSigners: List<String> = emptyList()
    ): InstallState = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "Plugin file not found" }
            val actualHash = ApkIntegrityVerifier.sha256(file)
            require(expectedSha256.isNullOrBlank() || actualHash.equals(expectedSha256, ignoreCase = true)) {
                "Plugin SHA-256 mismatch"
            }
            if (file.extension.equals("apk", true)) {
                val integrity = ApkIntegrityVerifier.verify(context, file, expectedPackage = null, expectedSha256 = actualHash)
                require(expectedSigners.isEmpty() || integrity.signerSha256.any { it in expectedSigners }) {
                    "Plugin APK signer is not trusted"
                }
            }
            val prepared = if (file.extension.equals("aiip", true)) extractPackage(file) else file
            val manifest = readManifest(prepared)
            require(manifest.compatible()) {
                "Unsupported plugin schema/API: ${manifest.schemaVersion}/${manifest.apiVersion}"
            }
            _state.value = InstallState.AwaitingPermission(prepared, manifest)
            InstallState.AwaitingPermission(prepared, manifest)
        }.getOrElse { InstallState.Failed(it.message ?: "Plugin install failed").also { _state.value = it } }
    }

    suspend fun confirmInstall(): InstallState = withContext(Dispatchers.IO) {
        val pending = _state.value as? InstallState.AwaitingPermission
            ?: return@withContext InstallState.Idle
        runCatching {
            val source = pending.packageFile
            val dex = if (source.isDirectory) File(source, "plugin.dex") else source
            require(dex.isFile) { "plugin.dex is missing" }
            val tools = sandbox.install(dex, pending.manifest)
            val instance = SandboxedPlugin(pending.manifest, sandbox, tools)
            val loaded = LoadedPlugin(pending.manifest, instance, source)
            installed[pending.manifest.id]?.close()
            installed[pending.manifest.id] = loaded
            _plugins.value = installed.values.toList()
            InstallState.Installed(loaded).also { _state.value = it }
        }.getOrElse { InstallState.Failed(it.message ?: "Plugin load failed").also { _state.value = it } }
    }

    fun rejectInstall() {
        _state.value = InstallState.Idle
    }

    suspend fun hotReload(): List<LoadedPlugin> = withContext(Dispatchers.IO) {
        val files = buildList {
            addAll(developmentDirectory().listFiles()?.filter { it.extension in setOf("dex", "jar", "aiip") }.orEmpty())
            addAll(context.getExternalFilesDir(null)?.listFiles()?.filter { it.extension in setOf("dex", "jar", "aiip") }.orEmpty())
        }
        files.forEach { install(it) }
        _plugins.value = installed.values.toList()
        _plugins.value
    }

    fun uninstall(id: String) {
        installed.remove(id)?.close()
        scope.launch { sandbox.unload(id) }
        _plugins.value = installed.values.toList()
    }

    private fun extractPackage(source: File): File {
        val target = File(context.cacheDir, "aiip/${source.nameWithoutExtension}").also { it.deleteRecursively(); it.mkdirs() }
        var total = 0L
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = File(target, entry.name)
                require(destination.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "Unsafe zip path" }
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> zip.copyTo(out) }
                    total += destination.length()
                    require(total <= MAX_PACKAGE_BYTES) { "Package is too large" }
                }
                zip.closeEntry()
            }
        }
        return target
    }

    private fun readManifest(source: File): PluginManifest {
        val manifestFile = if (source.isDirectory) File(source, "manifest.json") else File(source.parentFile, "${source.nameWithoutExtension}.manifest.json")
        return if (manifestFile.isFile) {
            val migrated = PluginManifestMigrator.migrate(
                json.parseToJsonElement(manifestFile.readText()).jsonObject
            )
            json.decodeFromJsonElement(PluginManifest.serializer(), migrated)
        } else {
            PluginManifest(source.nameWithoutExtension, source.nameWithoutExtension, "0", "", emptyList())
        }
    }

    companion object {
        private const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
    }
}

private class SandboxedPlugin(
    override val manifest: PluginManifest,
    private val client: PluginSandboxClient,
    private val cachedTools: List<PluginTool>
) : AiiaPlugin {
    override fun tools(): List<PluginTool> = cachedTools

    override suspend fun call(name: String, arguments: kotlinx.serialization.json.JsonObject): String =
        client.call(manifest.id, name, arguments)
}


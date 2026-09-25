package com.aiia.app.plugins.engine

import android.content.Context
import android.os.Environment
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
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
    val classLoader: DexClassLoader
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

    fun developmentDirectory(): File = File(
        Environment.getExternalStorageDirectory(),
        "AIIA/plugins"
    ).also { if (!it.exists()) it.mkdirs() }

    suspend fun install(file: File): InstallState = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "Plugin file not found" }
            val prepared = if (file.extension.equals("aiip", true)) extractPackage(file) else file
            val manifest = readManifest(prepared)
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
            val optimized = File(context.codeCacheDir, "aiia-plugin-${pending.manifest.id}").also { it.mkdirs() }
            val loader = DexClassLoader(dex.absolutePath, optimized.absolutePath, null, javaClass.classLoader)
            val raw = loader.loadClass(pending.manifest.entryClass).getDeclaredConstructor().newInstance()
            val instance = adapt(raw, pending.manifest)
            val loaded = LoadedPlugin(pending.manifest, instance, source, loader)
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

    private fun adapt(raw: Any, manifest: PluginManifest): AiiaPlugin {
        if (raw is AiiaPlugin) return raw
        val instance: Any = raw
        val rawClass = instance.javaClass
        return object : AiiaPlugin {
            override val manifest: PluginManifest = manifest
            override fun tools(): List<PluginTool> = runCatching {
                val method = instance.javaClass.getMethod("tools")
                val result = method.invoke(instance) as? List<*> ?: emptyList<Any>()
                result.mapNotNull { item ->
                    val value = item ?: return@mapNotNull null
                    val type = value.javaClass
                    val name = type.getMethod("getName").invoke(value) as? String ?: return@mapNotNull null
                    val description = type.getMethod("getDescription").invoke(value) as? String ?: ""
                    val schema = type.getMethod("getInputSchema").invoke(value) as? kotlinx.serialization.json.JsonObject
                        ?: kotlinx.serialization.json.buildJsonObject {}
                    PluginTool(name, description, schema)
                }
            }.getOrDefault(emptyList())
            override suspend fun call(name: String, arguments: kotlinx.serialization.json.JsonObject): String = runCatching {
                val method = rawClass?.getMethod(
                    "call",
                    String::class.java,
                    kotlinx.serialization.json.JsonObject::class.java
                ) ?: error("plugin call method not found")
                invokePluginMethod(instance, method, name, arguments)
            }.getOrElse { it.message ?: "plugin call failed" }
        }
    }

    private fun readManifest(source: File): PluginManifest {
        val manifestFile = if (source.isDirectory) File(source, "manifest.json") else File(source.parentFile, "${source.nameWithoutExtension}.manifest.json")
        return if (manifestFile.isFile) json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
        else PluginManifest(source.nameWithoutExtension, source.nameWithoutExtension, "0", "", emptyList())
    }

    companion object {
        private const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
    }
}

private fun invokePluginMethod(
    target: Any,
    method: java.lang.reflect.Method,
    name: String,
    arguments: kotlinx.serialization.json.JsonObject
): String = method.invoke(target!!, name, arguments).toString()


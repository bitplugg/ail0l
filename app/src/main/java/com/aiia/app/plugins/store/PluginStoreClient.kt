package com.aiia.app.plugins.store

import com.aiia.app.util.ApkIntegrityVerifier
import com.aiia.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException

data class DownloadedPlugin(
    val file: File,
    val sha256: String,
    val sizeBytes: Long
)

class PluginStoreClient(
    private val catalogUrl: String = DEFAULT_CATALOG_URL,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun catalog(): PluginStoreCatalog = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(catalogUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "AIIA/1.0")
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Catalog HTTP ${response.code}")
            json.decodeFromString(response.body?.string().orEmpty())
        }
    }

    suspend fun download(plugin: StorePlugin, directory: File): DownloadedPlugin = withContext(Dispatchers.IO) {
        val base = catalogUrl.substringBeforeLast('/').substringBeforeLast('/')
        val url = if (plugin.artifact.startsWith("http")) plugin.artifact else "$base/${plugin.artifact}"
        val target = File(directory, "${plugin.slug}.${plugin.format}")
        val partial = File(directory, "${target.name}.part")
        directory.mkdirs()
        val request = Request.Builder().url(url).header("User-Agent", "AIIA/1.0").get().build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Plugin HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty plugin response")
            body.byteStream().use { input -> partial.outputStream().buffered().use { output -> input.copyTo(output) } }
        }
        val digest = ApkIntegrityVerifier.sha256(partial)
        val expectedHash = plugin.sha256?.takeIf { it.isNotBlank() }
        if (expectedHash != null && !digest.equals(expectedHash, ignoreCase = true)) {
            partial.delete()
            throw IOException("SHA-256 mismatch for ${plugin.slug}")
        }
        if (plugin.sizeBytes > 0 && partial.length() != plugin.sizeBytes) {
            partial.delete()
            throw IOException("Size mismatch for ${plugin.slug}")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        DownloadedPlugin(target, digest, target.length())
    }

    companion object {
        const val DEFAULT_CATALOG_URL = "https://bitplugg.github.io/aiia-plugin-store/catalog/catalog.json"
    }
}

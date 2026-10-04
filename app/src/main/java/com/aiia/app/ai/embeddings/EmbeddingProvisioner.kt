package com.aiia.app.ai.embeddings

import android.content.Context
import com.aiia.app.agent.VectorSearchEngine
import com.aiia.app.data.SettingsRepository
import com.aiia.app.util.Http
import com.aiia.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

data class EmbeddingProvisionState(
    val status: String = "idle",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val modelPath: String = "",
    val error: String? = null
) {
    val running: Boolean get() = status == "downloading"

    fun percent(): Int = if (totalBytes <= 0L) {
        0
    } else {
        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }
}

/**
 * Downloads an ONNX embedding model plus its vocabulary into app storage and points the settings
 * at it. The model is intentionally not registered in the `models` table: that table feeds
 * `installedModel()` and would hand an .onnx file to the chat engine as if it were a GGUF.
 */
class EmbeddingProvisioner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val vectorSearch: VectorSearchEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(EmbeddingProvisionState())
    val state: StateFlow<EmbeddingProvisionState> = _state.asStateFlow()

    fun directory(spec: EmbeddingModelSpec = EmbeddingModels.DEFAULT): java.io.File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return java.io.File(java.io.File(base, "models").let { java.io.File(it, "embeddings") }, spec.directoryName())
            .also { it.mkdirs() }
    }

    fun isInstalled(spec: EmbeddingModelSpec = EmbeddingModels.DEFAULT): Boolean =
        java.io.File(directory(spec), spec.modelFile.substringAfterLast('/')).isFile &&
            java.io.File(directory(spec), spec.vocabFile).isFile

    /** Downloads the default model unless one is already configured and present. */
    fun ensure() {
        scope.launch {
            val configured = settings.settings.first().embeddingModelPath
            val ready = configured.isNotBlank() && java.io.File(configured).isFile
            if (ready) vectorSearch.loadConfiguredModel() else runInstall(EmbeddingModels.DEFAULT)
        }
    }

    fun install(spec: EmbeddingModelSpec) {
        if (_state.value.running) return
        scope.launch { runInstall(spec) }
    }

    private suspend fun runInstall(spec: EmbeddingModelSpec) {
        val token = settings.settings.first().hfToken
        val target = directory(spec)
        val modelName = spec.modelFile.substringAfterLast('/')
        val modelFile = java.io.File(target, modelName)
        val vocabFile = java.io.File(target, spec.vocabFile)

        try {
            _state.value = EmbeddingProvisionState(status = "downloading", totalBytes = spec.sizeBytes)
            Notifications.ensureModelChannel(context)

            if (!vocabFile.isFile) fetch(spec.vocabUrl, vocabFile, token)
            if (modelFile.length() < spec.sizeBytes) fetch(spec.modelUrl, modelFile, token)

            settings.setRag(true, modelFile.absolutePath, spec.dimensions)
            vectorSearch.loadConfiguredModel()
            vectorSearch.reindexIfStale()
            _state.value = EmbeddingProvisionState(
                status = "installed",
                downloadedBytes = spec.sizeBytes,
                totalBytes = spec.sizeBytes,
                modelPath = modelFile.absolutePath
            )
        } catch (e: Exception) {
            _state.value = EmbeddingProvisionState(status = "failed", error = e.message)
        }
    }

    private suspend fun fetch(url: String, target: java.io.File, token: String) = withContext(Dispatchers.IO) {
        val part = java.io.File(target.parentFile, target.name + ".part")
        // A finished previous file is trusted; anything else resumes from the .part length.
        val start = if (part.isFile) part.length() else 0L
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "AIIA/1.0")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (start > 0L) builder.header("Range", "bytes=$start-")

        val response = Http.client.newCall(builder.build()).execute()
        val resuming = start > 0L && response.code == 206
        if (!resuming && part.exists()) part.delete()
        val body = response.body ?: throw IllegalStateException("Пустой ответ для $url")
        val expected = response.header("Content-Length")?.toLongOrNull()?.plus(if (resuming) start else 0L)
        response.use { streamTo(part, body, resuming) }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Не удалось сохранить $url")
        if (expected != null && expected > 0L && target.length() < expected) {
            throw IllegalStateException("Файл докачан не полностью")
        }
    }

    private fun streamTo(part: java.io.File, body: okhttp3.ResponseBody, append: Boolean) {
        java.io.FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var read = input.read(buffer)
                while (read >= 0) {
                    out.write(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        }
    }
}

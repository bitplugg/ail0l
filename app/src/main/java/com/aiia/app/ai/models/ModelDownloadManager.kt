package com.aiia.app.ai.models

import android.content.Context
import com.aiia.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class ModelDownloadProgress(
    val artifact: ModelArtifact,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long = 0,
    val state: State = State.DOWNLOADING
) {
    enum class State { DOWNLOADING, PAUSED, COMPLETED, FAILED }
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

class ModelDownloadManager(
    context: Context,
    private val token: String = "",
    private val onProgress: (ModelDownloadProgress) -> Unit = {}
) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    private val cancelled = AtomicBoolean(false)

    init { root.mkdirs() }

    fun destination(artifact: ModelArtifact): File = File(
        root,
        safeName(artifact.repository.replace('/', '_')) + "__" + safeName(artifact.filename)
    )

    fun isPaused(artifact: ModelArtifact): Boolean = File(destination(artifact).absolutePath + ".part").isFile

    fun cancel() { cancelled.set(true) }

    fun download(artifact: ModelArtifact): Flow<ModelDownloadProgress> = flow {
        cancelled.set(false)
        val target = destination(artifact)
        val part = File(target.absolutePath + ".part")
        target.parentFile?.mkdirs()
        var existing = part.length()
        if (existing >= artifact.sizeBytes && artifact.sizeBytes > 0) {
            part.delete()
            existing = 0
        }

        val builder = Request.Builder()
            .url(artifact.downloadUrl)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "AIIA/1.0")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val response = Http.client.newCall(builder.build()).execute()
        response.use { resp ->
            if (resp.code == 416) {
                part.delete()
                throw IOException("Сервер уже считает файл загруженным; начните загрузку заново")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(240).orEmpty()}")
            val resume = existing > 0 && resp.code == 206
            if (existing > 0 && !resume) {
                part.delete()
                existing = 0
            }
            val total = parseTotal(resp.header("Content-Range"), resp.header("Content-Length"), existing, artifact.sizeBytes)
            val source = resp.body?.source() ?: throw IOException("Пустой ответ сервера")
            val buffer = ByteArray(128 * 1024)
            var downloaded = existing
            var lastAt = System.nanoTime()
            var lastBytes = downloaded
            FileOutputStream(part, resume).use { out ->
                while (true) {
                    if (cancelled.get()) {
                        emit(ModelDownloadProgress(artifact, downloaded, total, state = ModelDownloadProgress.State.PAUSED))
                        return@flow
                    }
                    val count = source.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    downloaded += count
                    val now = System.nanoTime()
                    if (now - lastAt >= 400_000_000L) {
                        val speed = ((downloaded - lastBytes) * 1_000_000_000L / (now - lastAt)).coerceAtLeast(0)
                        val progress = ModelDownloadProgress(artifact, downloaded, total, speed)
                        onProgress(progress)
                        emit(progress)
                        lastAt = now
                        lastBytes = downloaded
                    }
                }
            }
            if (total > 0 && downloaded < total) throw IOException("Загрузка прервана: $downloaded из $total байт")
            if (artifact.sizeBytes > 0 && downloaded != artifact.sizeBytes) {
                part.delete()
                throw IOException("Размер файла не совпадает: $downloaded вместо ${artifact.sizeBytes}")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось переименовать файл модели")
            artifact.sha256?.let { expected ->
                val digest = sha256(target)
                if (!digest.equals(expected, ignoreCase = true)) {
                    target.delete()
                    throw IOException("SHA-256 не совпадает для ${artifact.filename}")
                }
            }
            val done = ModelDownloadProgress(artifact, target.length(), total.coerceAtLeast(target.length()), state = ModelDownloadProgress.State.COMPLETED)
            onProgress(done)
            emit(done)
        }
    }.flowOn(Dispatchers.IO)

    private fun parseTotal(contentRange: String?, contentLength: String?, existing: Long, advertised: Long): Long {
        contentRange?.substringAfter('/')?.toLongOrNull()?.let { return it }
        contentLength?.toLongOrNull()?.let { return if (existing > 0) existing + it else it }
        return advertised
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

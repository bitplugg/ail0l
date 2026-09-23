package com.ail0l.app.ai.download

import android.content.Context
import com.ail0l.app.data.AppDatabase
import com.ail0l.app.data.entities.ModelEntity
import com.ail0l.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBps: Long = 0,
    val done: Boolean = false
)

/**
 * Загрузчик GGUF-моделей с Hugging Face с возобновлением (HTTP Range).
 * Модель пишется во «временный» файл `.part`, по завершении — переименовывается.
 */
class HfDownloader(
    private val context: Context,
    private val db: AppDatabase,
    private val hfToken: String
) {

    fun modelsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "models").also { it.mkdirs() }
    }

    fun localPath(entry: CatalogEntry): File =
        File(modelsDir(), "${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf")

    fun isInstalled(entry: CatalogEntry): Boolean = localPath(entry).isFile

    fun download(entry: CatalogEntry): Flow<DownloadProgress> = flow {
        val target = localPath(entry)
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.parentFile?.mkdirs()

        var existing = tmp.length()
        // если надоевший .part больше целевого файла — это кусок устаревшей загрузки
        if (existing > entry.sizeBytes) {
            tmp.delete()
            existing = 0
        }

        val resume = existing > 0
        val builder = Request.Builder().url(entry.downloadUrl)
        if (resume) builder.header("Range", "bytes=$existing-")
        if (hfToken.isNotBlank()) builder.header("Authorization", "Bearer $hfToken")

        val response = Http.client.newCall(builder.build()).execute()
        if (resume && response.code != 206) {
            // сервер не поддержал Range — начинаем заново
            response.close()
            tmp.delete()
            existing = 0
        } else if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            response.close()
            throw IOException("HTTP ${response.code}: ${body.take(300)}")
        }

        val total = entry.sizeBytes
        var downloaded = existing
        response.body?.use { body ->
            val src = body.source()
            val buffer = ByteArray(64 * 1024)
            FileOutputStream(tmp, resume).use { out ->
                var since = downloaded
                var lastNanos = System.nanoTime()
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    downloaded += n
                    val now = System.nanoTime()
                    if (now - lastNanos >= 500_000_000L) {
                        val speed = (downloaded - since) * 1_000_000_000L / (now - lastNanos)
                        since = downloaded
                        lastNanos = now
                        emit(DownloadProgress(downloaded, total, speed))
                    }
                }
            }
        }
        response.close()

        if (downloaded < total) {
            throw IOException("Загрузка оборвалась: $downloaded из $total байт")
        }

        target.delete()
        if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл модели")

        db.dao().upsertModel(
            ModelEntity(
                repo = entry.repo,
                filename = entry.filename,
                family = entry.family,
                paramsLabel = entry.paramsLabel,
                quant = entry.quant,
                sizeBytes = target.length(),
                modelFile = target.absolutePath,
                installed = true
            )
        )
        emit(DownloadProgress(downloaded, total, 0, done = true))
    }.flowOn(Dispatchers.IO)
}
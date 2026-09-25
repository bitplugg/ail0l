package com.aiia.app.ai.download

import android.content.Context
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.entities.ModelEntity
import com.aiia.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBps: Long = 0,
    val done: Boolean = false
)

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
        if (entry.sizeBytes > 0 && existing > entry.sizeBytes) {
            tmp.delete()
            existing = 0
        }
        var resume = existing > 0
        var response = execute(entry, existing, resume)
        if (resume && response.code != 206) {
            response.close()
            tmp.delete()
            existing = 0
            resume = false
            response = execute(entry, 0, false)
        }
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            response.close()
            throw IOException("HTTP ${response.code}: ${body.take(300)}")
        }
        val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            ?: response.header("Content-Length")?.toLongOrNull()?.plus(existing)
            ?: entry.sizeBytes
        var downloaded = existing
        response.use { resp ->
            val body = resp.body ?: throw IOException("Пустой ответ сервера")
            body.source().use { source ->
                val buffer = ByteArray(128 * 1024)
                FileOutputStream(tmp, resume).use { output ->
                    var lastAt = System.nanoTime()
                    var lastBytes = downloaded
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastAt >= 500_000_000L) {
                            val speed = (downloaded - lastBytes) * 1_000_000_000L / (now - lastAt)
                            emit(DownloadProgress(downloaded, total, speed))
                            lastAt = now
                            lastBytes = downloaded
                        }
                    }
                }
            }
        }
        if (total > 0 && downloaded < total) throw IOException("Загрузка оборвалась: $downloaded из $total байт")
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл модели")
        db.dao().upsertModel(ModelEntity(
            repo = entry.repo,
            filename = entry.filename,
            family = entry.family,
            paramsLabel = entry.paramsLabel,
            quant = entry.quant,
            sizeBytes = target.length(),
            modelFile = target.absolutePath,
            installed = true
        ))
        emit(DownloadProgress(downloaded, total, 0, done = true))
    }.flowOn(Dispatchers.IO)

    private fun execute(entry: CatalogEntry, offset: Long, resume: Boolean): Response {
        val builder = Request.Builder().url(entry.downloadUrl)
        if (resume) builder.header("Range", "bytes=$offset-")
        if (hfToken.isNotBlank()) builder.header("Authorization", "Bearer $hfToken")
        return Http.client.newCall(builder.build()).execute()
    }
}

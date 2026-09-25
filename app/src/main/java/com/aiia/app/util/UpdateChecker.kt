package com.aiia.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val apkUrl: String?
)

object UpdateChecker {
    private const val REPO = "https://api.github.com/repos/bitplugg/aiia"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun latestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$REPO/releases/latest")
                .header("User-Agent", "AIIA/1.0")
                .get()
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val body = r.body?.string() ?: return@runCatching null
                val root = Json.parseToJsonElement(body).jsonObject
                val assets = (root["assets"]?.jsonArray ?: emptyList())
                val apkUrl = assets.firstOrNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
                }?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                ReleaseInfo(
                    tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty() },
                    body = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    apkUrl = apkUrl
                )
            }
        }.getOrNull()
    }

    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        fun nums(s: String): List<Int> =
            s.trim().trimStart('v').split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val a = nums(latestTag)
        val b = nums(currentVersion)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun downloadApk(url: String, file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "AIIA/1.0").get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching false
                val src = r.body?.byteStream() ?: return@runCatching false
                file.outputStream().buffered(1 shl 20).use { out -> src.copyTo(out, 1 shl 20) }
                true
            }
        }.getOrDefault(false)
    }

    suspend fun downloadApkToDownloads(context: Context, url: String, tag: String): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = "aiia-${tag.trimStart('v')}.apk"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                val ok = resolver.openOutputStream(uri)?.use { out ->
                    val req = Request.Builder().url(url).header("User-Agent", "AIIA/1.0").get().build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) {
                            false
                        } else {
                            r.body?.byteStream()?.use { it.copyTo(out, 1 shl 20) }
                            true
                        }
                    }
                } ?: false
                if (!ok) {
                    resolver.delete(uri, null, null)
                    null
                } else uri
            }.getOrNull()
        }
}

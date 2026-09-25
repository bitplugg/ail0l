package com.aiia.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

suspend fun streamSseData(
    request: Request,
    onData: suspend (String) -> Unit
): Unit = withContext(Dispatchers.IO) {
    val response = Http.client.newCall(request).execute()
    if (!response.isSuccessful) {
        val body = response.body?.string().orEmpty()
        response.close()
        throw IOException("HTTP ${response.code}: ${body.take(300)}")
    }
    response.use { resp ->
        val source = resp.body?.source() ?: return@withContext
        var sawDone = false
        while (!sawDone) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) {
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    sawDone = true
                } else if (payload.isNotEmpty()) {
                    onData(payload)
                }
            }
        }
    }
}

suspend fun executeJson(request: Request): String = withContext(Dispatchers.IO) {
    Http.client.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) {
            val body = resp.body?.string().orEmpty()
            throw IOException("HTTP ${resp.code}: ${body.take(300)}")
        }
        resp.body?.string().orEmpty()
    }
}

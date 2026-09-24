package com.ail0l.app.ai.models

import com.ail0l.app.util.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException

/** Lightweight Hugging Face API client. It never changes the existing curated catalog. */
class HfModelClient(private val token: String = "") {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<HfModel> {
        if (query.isBlank()) return emptyList()
        val url = "https://huggingface.co/api/models?search=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&filter=gguf&limit=20"
        val request = Request.Builder().url(url).apply {
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }.get().build()
        val raw = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        return json.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            obj["id"]?.jsonPrimitive?.content?.let { HfModel(it) }
        }
    }

    suspend fun files(repo: String): List<HfFile> {
        val url = "https://huggingface.co/api/models/${repo.trim('/') }?blobs=true"
        val request = Request.Builder().url(url).apply {
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }.get().build()
        val raw = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val siblings = json.parseToJsonElement(raw).jsonObject["siblings"]?.jsonArray ?: JsonArray(emptyList())
        return siblings.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["rfilename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!name.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull
                ?: obj["lfs"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
            HfFile(repo, name, size)
        }
    }
}

data class HfModel(val id: String)
data class HfFile(val repo: String, val filename: String, val sizeBytes: Long) {
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$filename"
    val quant: String get() = Regex("(?i)(Q\\d[_A-Z0-9]+)").find(filename)?.groupValues?.get(1)?.uppercase() ?: "GGUF"
}

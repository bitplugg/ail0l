package com.ail0l.app.ai.models

import com.ail0l.app.util.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/** Поиск GGUF-моделей в Hugging Face и разбор списка файлов модели. */
class HfModelClient(private val token: String = "") {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<HfModel> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$encoded&filter=gguf&limit=20"
        val request = Request.Builder()
            .url(url)
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .get()
            .build()

        val raw = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face HTTP ${response.code}")
            response.body?.string().orEmpty()
        }

        return json.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val downloads = obj["downloads"]?.jsonPrimitive?.longOrNull ?: 0L
            val tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            HfModel(id = id, downloads = downloads, tags = tags)
        }
    }

    suspend fun files(repo: String): List<HfFile> {
        val cleanRepo = repo.trim().trim('/')
        if (cleanRepo.isBlank()) return emptyList()

        val encodedRepo = URLEncoder.encode(cleanRepo, "UTF-8")
        val url = "https://huggingface.co/api/models/$encodedRepo?blobs=true"
        val request = Request.Builder()
            .url(url)
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .get()
            .build()

        val raw = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face HTTP ${response.code}")
            response.body?.string().orEmpty()
        }

        val root = json.parseToJsonElement(raw).jsonObject
        val siblings = root["siblings"]?.jsonArray ?: JsonArray(emptyList())

        return siblings.mapNotNull { element ->
            val obj = element.jsonObject
            val filename = obj["rfilename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!filename.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            val sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull
                ?: obj["lfs"]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
                ?: 0L
            HfFile(
                repo = cleanRepo,
                filename = filename,
                sizeBytes = sizeBytes,
                quant = inferQuant(filename)
            )
        }.sortedByDescending { it.sizeBytes }
    }

    companion object {
        private val QUANT_RE = Regex("(?i)(Q[0-9]_[A-Z0-9]+|Q[0-9][A-Z0-9_]+)")

        fun inferQuant(filename: String): String {
            val match = QUANT_RE.find(filename) ?: return "GGUF"
            return match.groupValues[1].uppercase()
        }
    }
}

data class HfModel(
    val id: String,
    val downloads: Long = 0L,
    val tags: List<String> = emptyList()
)

data class HfFile(
    val repo: String,
    val filename: String,
    val sizeBytes: Long,
    val quant: String = HfModelClient.inferQuant(filename)
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repo/resolve/main/$filename"

    val family: String
        get() = repo.substringAfterLast('/').substringBefore("-GGUF").substringBefore("-gguf")
            .ifBlank { "HF" }

    val paramsLabel: String
        get() = repo.substringAfterLast('/').let { value ->
            val withoutExt = value.substringBeforeLast("-GGUF").substringBeforeLast("-gguf")
            Regex("(\\d+\\.?\\d*[BM])", RegexOption.IGNORE_CASE)
                .find(withoutExt)?.value ?: "custom"
        }
}

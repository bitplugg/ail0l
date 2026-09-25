package com.aiia.app.ai.models

import com.aiia.app.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

class HuggingFaceModelsApi(
    private val baseUrl: String = "https://huggingface.co/api/models",
    private val token: String = ""
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int = 50): List<CatalogModel> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val url = "$baseUrl?search=${encode(q)}&filter=gguf&limit=${limit.coerceIn(1, 100)}"
        val body = request(url)
        val array = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { return@withContext emptyList() }
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val model = runCatching { json.decodeFromJsonElement(HuggingFaceModel.serializer(), obj) }.getOrNull()
                ?: return@mapNotNull null
            val artifacts = obj["siblings"]?.jsonArray.orEmpty().mapNotNull { sibling ->
                val s = sibling as? JsonObject ?: return@mapNotNull null
                parseArtifact(model.id, s)
            }
            val complete = if (artifacts.isEmpty()) repositoryFiles(model.id) else artifacts
            CatalogModel(model, complete)
        }
    }

    suspend fun repositoryFiles(repository: String): List<ModelArtifact> = withContext(Dispatchers.IO) {
        val body = request("${baseUrl}/${repository.trim('/')}?blobs=true")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext emptyList()
        root["siblings"]?.jsonArray.orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.let { parseArtifact(repository, it) }
        }
    }

    private fun request(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "AIIA/1.0")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .get()
            .build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Hugging Face HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun parseArtifact(repository: String, obj: JsonObject): ModelArtifact? {
        val filename = obj["rfilename"]?.jsonPrimitive?.content ?: return null
        if (!filename.endsWith(".gguf", ignoreCase = true)) return null
        val lfs = obj["lfs"] as? JsonObject
        val size = lfs?.get("size")?.jsonPrimitive?.longOrNull
            ?: obj["size"]?.jsonPrimitive?.longOrNull
            ?: 0L
        val kind = if (filename.startsWith("mmproj", ignoreCase = true) ||
            filename.contains("mmproj", ignoreCase = true)
        ) ModelArtifactKind.MMPROJ else ModelArtifactKind.MODEL
        val quant = Quantization.fromFileName(filename).label.takeUnless { it == "Other" }
        return ModelArtifact(
            repository = repository,
            filename = filename,
            kind = kind,
            quantization = quant,
            sizeBytes = size,
            downloadUrl = "https://huggingface.co/$repository/resolve/main/${encodePath(filename)}",
            sha256 = lfs?.get("sha256")?.jsonPrimitive?.content
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encodePath(value: String): String = value.split('/').joinToString("/") { encode(it) }
}

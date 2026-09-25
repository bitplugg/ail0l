package com.aiia.app.ai.models

import com.aiia.app.util.Http
import kotlinx.coroutines.CancellationException
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
        val bounded = limit.coerceIn(1, 50)
        val urls = listOf(
            "$baseUrl?search=${encode(q)}&filter=gguf&blobs=true&limit=$bounded",
            "$baseUrl?search=${encode(q)}&blobs=true&limit=$bounded"
        )
        var array: JsonArray? = null
        for (url in urls) {
            val parsed = runCatching {
                json.parseToJsonElement(request(url)).jsonArray
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                array = parsed
                break
            }
        }
        val results = buildList {
            for (element in array.orEmpty()) {
                val obj = element as? JsonObject ?: continue
                val model = runCatching {
                    json.decodeFromJsonElement(HuggingFaceModel.serializer(), obj)
                }.getOrNull() ?: continue
                val siblings = runCatching { obj["siblings"]?.jsonArray.orEmpty() }
                    .getOrDefault(emptyList())
                val artifacts = siblings.mapNotNull { sibling ->
                    if (sibling is JsonObject) {
                        runCatching { parseArtifact(model.id, sibling) }.getOrNull()
                    } else {
                        null
                    }
                }
                val complete = if (artifacts.isEmpty()) {
                    repositoryFilesOrEmpty(model.id)
                } else {
                    artifacts
                }
                if (complete.isNotEmpty()) add(CatalogModel(model, complete))
            }
        }
        results.distinctBy { it.id }.take(bounded)
    }

    suspend fun repositoryFiles(repository: String): List<ModelArtifact> = withContext(Dispatchers.IO) {
        val body = request("${baseUrl}/${repository.trim('/')}?blobs=true")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext emptyList()
        root["siblings"]?.jsonArray.orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.let { parseArtifact(repository, it) }
        }
    }

    private suspend fun repositoryFilesOrEmpty(repository: String): List<ModelArtifact> =
        try {
            repositoryFiles(repository)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
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

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun encodePath(value: String): String = value.split('/').joinToString("/") { encode(it) }
}

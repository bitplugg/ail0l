package com.aiia.app.ai.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class SearchResult(val title: String, val snippet: String, val url: String)

class WebSearch(private val client: OkHttpClient = defaultClient()) {

    suspend fun search(query: String, customUrl: String = "", customKey: String = ""): List<SearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            supervisorScope {
                runCatching { searchCustom(q, customUrl, customKey) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: runCatching { searchDuckDuckGo(q) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?: runCatching { searchWikipedia(q) }.getOrDefault(emptyList())
            }
        }
    }

    private suspend fun searchDuckDuckGo(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "https://api.duckduckgo.com/"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()

        val body = execute(url.toString())
        if (body.isNullOrBlank()) return@withContext emptyList()

        val root = Json.parseToJsonElement(body).jsonObject
        val answers = buildList {
            root["AbstractText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                add(SearchResult("Краткий ответ", it, root["AbstractURL"]?.jsonPrimitive?.contentOrNull ?: ""))
            }
            root["Answer"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                add(SearchResult("Ответ", it, ""))
            }
            root["RelatedTopics"]?.jsonArray?.forEach { topic ->
                val obj = topic.jsonObject
                val title = obj["Text"]?.jsonPrimitive?.contentOrNull
                if (title != null) {
                    add(SearchResult(title.take(120), title, obj["FirstURL"]?.jsonPrimitive?.contentOrNull ?: ""))
                }
            }
        }
        answers.distinctBy { it.title }.take(5)
    }

    private suspend fun searchWikipedia(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "https://ru.wikipedia.org/w/api.php"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", query)
            .addQueryParameter("srlimit", "5")
            .addQueryParameter("format", "json")
            .addQueryParameter("utf8", "1")
            .build()

        val body = execute(url.toString())
        if (body.isNullOrBlank()) return@withContext emptyList()

        val root = Json.parseToJsonElement(body).jsonObject
        val search = root["query"]?.jsonObject?.get("search")?.jsonArray ?: emptyList()
        search.mapNotNull { el ->
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace(Regex("<[^>]+>"), "").replace("&#160;", " ").trim()
            SearchResult(
                title = title,
                snippet = snippet.take(200),
                url = "https://ru.wikipedia.org/wiki/${title.replace(" ", "_")}"
            )
        }.take(5)
    }

    private suspend fun searchCustom(query: String, customUrl: String, customKey: String): List<SearchResult> {
        val base = customUrl.trim()
        if (base.isBlank()) return emptyList()

        val httpUrl = try {
            base.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .build()
        } catch (e: Exception) {
            return emptyList()
        }

        val builder = Request.Builder().url(httpUrl).get()
        if (customKey.isNotBlank()) builder.header("Authorization", "Bearer $customKey")

        val body = withContext(Dispatchers.IO) { execute(builder.build()) }
        if (body.isNullOrBlank()) return emptyList()

        return runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            (root["results"]?.jsonArray ?: emptyList()).mapNotNull { el ->
                val o = el.jsonObject
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SearchResult(
                    title = title,
                    snippet = o["content"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200),
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun execute(url: String): String? =
        execute(Request.Builder().url(url).header("User-Agent", "AIIA/1.0").get().build())

    private suspend fun execute(request: Request): String? = suspendCoroutine { cont ->
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                cont.resume(null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { r ->
                    cont.resume(if (r.isSuccessful && r.body != null) r.body!!.string() else null)
                }
            }
        })
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

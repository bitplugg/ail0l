package com.ail0l.app.ai.engines

import com.ail0l.app.ai.ChatMessage
import com.ail0l.app.util.streamSseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic-совместимый движок (Claude). Стриминг по SSE.
 * Системный промпт уходит в топ-уровневое поле `system`.
 */
class AnthropicEngine(
    val apiKey: String,
    val model: String,
    override val label: String = model,
    val baseUrl: String = "https://api.anthropic.com"
) : AiEngine {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(messages: List<ChatMessage>, predictLength: Int?): Flow<String> = flow {
        val system = messages.filter { it is ChatMessage.System }.joinToString("\n\n") { it.content }
        val turns = messages.filterNot { it is ChatMessage.System }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", (predictLength ?: 1024).coerceIn(16, 8192))
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                turns.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
        }.toString().toRequestBody(CONTENT_TYPE)

        val url = baseUrl.trimEnd('/') + "/v1/messages"
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body)
            .build()

        streamSseData(request) { payload ->
            val obj = json.parseToJsonElement(payload).jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta") {
                val text = obj["delta"]
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                if (!text.isNullOrEmpty()) emit(text)
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val CONTENT_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
package com.ail0l.app.ai.engines

import com.ail0l.app.ai.ChatMessage
import com.ail0l.app.util.streamSseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
 * OpenAI-совместимый движок: чат с потоковым SSE-стримингом.
 * Покрывает OpenAI, а также Mistral (baseUrl = https://api.mistral.ai/v1)
 * и любые self-hosted серверы (vLLM, llama-server, LM Studio…).
 */
class OpenAiEngine(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    override val label: String = model
) : AiEngine {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(messages: List<ChatMessage>): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
        }.toString().toRequestBody(CONTENT_TYPE)

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        streamSseData(request) { payload ->
            val obj = json.parseToJsonElement(payload).jsonObject
            val delta = obj["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("delta")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
            if (!delta.isNullOrEmpty()) emit(delta)
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val CONTENT_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
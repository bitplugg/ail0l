package com.aiia.app.ai.engines

import com.aiia.app.ai.ChatMessage
import com.aiia.app.util.streamSseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import android.util.Base64
import java.io.File
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
                    val images = (msg as? ChatMessage.User)?.images.orEmpty()
                    if (images.isEmpty()) {
                        addJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    } else {
                        addJsonObject {
                            put("role", msg.role)
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                }
                                images.forEach { image ->
                                    addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", image.mimeType)
                                            put("data", imageBase64(image.uri))
                                        }
                                    }
                                }
                            }
                        }
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

    private fun imageBase64(uri: String): String {
        val file = File(uri)
        return if (file.isFile) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else ""
    }

    private companion object {
        val CONTENT_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

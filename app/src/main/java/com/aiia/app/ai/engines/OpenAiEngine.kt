package com.aiia.app.ai.engines

import com.aiia.app.ai.ChatMessage
import com.aiia.app.util.streamSseData
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
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import android.util.Base64
import java.io.File
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiEngine(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    override val label: String = model
) : AiEngine {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(messages: List<ChatMessage>, predictLength: Int?): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", (predictLength ?: 1024).coerceIn(16, 8192))
            putJsonArray("messages") {
                messages.forEach { msg ->
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
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", imageDataUrl(image.uri, image.mimeType))
                                        }
                                    }
                                }
                            }
                        }
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

    private fun imageDataUrl(uri: String, mime: String): String {
        val file = File(uri)
        return if (file.isFile) {
            "data:$mime;base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}"
        } else uri
    }

    private companion object {
        val CONTENT_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

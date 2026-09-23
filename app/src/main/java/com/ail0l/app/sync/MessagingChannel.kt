package com.ail0l.app.sync

import com.ail0l.app.util.Http
import com.ail0l.app.util.executeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

@Serializable
data class WireMessage(
    val id: String,
    val conversationId: Long? = null,
    val sender: String,
    val content: String,
    val toDevice: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PushRequest(
    val device: String,
    val messages: List<WireMessage>
)

@Serializable
data class PullResponse(
    val messages: List<WireMessage> = emptyList()
)

/**
 * REST-канал для обмена сообщениями между агентами.
 *
 *   POST /{device}/messages   — отправить исходящие
 *   GET  /{device}/messages?after={ts} — забрать новые входящие
 *
 * Аутентификация — заголовок X-Ail0l-Key.
 */
class MessagingChannel(private val baseUrl: String, private val key: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Request.Builder.auth(): Request.Builder {
        if (key.isNotBlank()) header("X-Ail0l-Key", key)
        return this
    }

    suspend fun push(device: String, messages: List<WireMessage>) {
        val body = PushRequest(device, messages)
        val payload = json.encodeToString(PushRequest.serializer(), body)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/${device.encodeUrl()}/messages")
            .auth()
            .post(payload.toRequestBody(JSON))
            .build()
        runCatching { executeJson(request) }
    }

    /** Возвращает новые входящие сообщения после [afterTs] */
    suspend fun pull(device: String, afterTs: Long): List<WireMessage> {
        val url = "${baseUrl.trimEnd('/')}/${device.encodeUrl()}/messages?after=$afterTs&limit=100"
        val request = Request.Builder()
            .url(url)
            .auth()
            .get()
            .build()
        val raw = executeJson(request)
        return runCatching { json.decodeFromString(PullResponse.serializer(), raw).messages }
            .getOrElse { throw IOException("Не удалось разобрать ответ сервера: ${raw.take(200)}") }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun newId(): String = UUID.randomUUID().toString()
    }
}
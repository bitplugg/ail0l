package com.aiia.app.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aiia.app.AiiaApp
import com.aiia.app.R
import com.aiia.app.agent.Agent
import com.aiia.app.data.SettingsRepository
import com.aiia.app.data.entities.ConversationEntity
import com.aiia.app.dm.Dependencies
import com.aiia.app.sync.P2pReceiver
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class ApiServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = Channel<ApiJob>(Channel.UNLIMITED)
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    private var p2pServer: io.ktor.server.engine.ApplicationEngine? = null
    private var settings: SettingsRepository? = null
    private var accessToken: String = ""
    private var syncKey: String = ""
    private var port: Int = 8080

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        val snapshot = runBlocking { settings?.settings?.first() }
        port = snapshot?.apiPort?.coerceIn(1024, 65535) ?: 8080
        accessToken = snapshot?.apiToken.orEmpty()
        syncKey = snapshot?.syncPassword.orEmpty()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Локальный API: 127.0.0.1:$port"))
        scope.launch { consumeRequests() }
        startServer(port)
        val p2pPort = snapshot?.p2pPort?.coerceIn(1024, 65535) ?: 8081
        if (snapshot?.p2pEnabled == true && p2pPort != port) startP2pServer(p2pPort)
    }

    private fun startServer(bindPort: Int) {
        server = embeddedServer(CIO, host = "127.0.0.1", port = bindPort) {
            install(ContentNegotiation) { json(json) }
            routing {
                get("/health") {
                    call.respond(mapOf("status" to "ok", "service" to "aiia"))
                }
                get("/v1/models") {
                    if (!call.authorized()) return@get
                    call.respond(buildJsonObject {
                        put("object", JsonPrimitive("list"))
                        put("data", kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject {
                                put("id", JsonPrimitive("local"))
                                put("object", JsonPrimitive("model"))
                            })
                        })
                    })
                }
                post("/v1/chat/completions") {
                    if (!call.authorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                        return@post
                    }
                    val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }
                        .getOrNull()
                        ?: run {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid json"))
                            return@post
                        }
                    val job = ApiJob(
                        request = body,
                        output = Channel(Channel.UNLIMITED),
                        done = CompletableDeferred()
                    )
                    requests.send(job)
                    if (body["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            for (chunk in job.output) {
                                if (chunk == DONE) {
                                    write("data: [DONE]\n\n")
                                    flush()
                                    break
                                }
                                write("data: ${chunk}\n\n")
                                flush()
                            }
                        }
                    } else {
                        val text = buildString {
                            for (chunk in job.output) {
                                if (chunk != DONE) append(json.parseToJsonElement(chunk).jsonObject["choices"]
                                    ?.jsonArray?.firstOrNull()?.jsonObject
                                    ?.get("message")?.jsonObject
                                    ?.get("content")?.jsonPrimitive?.content.orEmpty())
                            }
                        }
                        call.respond(buildJsonObject {
                            put("id", JsonPrimitive(UUID.randomUUID().toString()))
                            put("object", JsonPrimitive("chat.completion"))
                            put("choices", kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("index", JsonPrimitive(0))
                                    put("message", buildJsonObject { put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive(text)) })
                                    put("finish_reason", JsonPrimitive("stop"))
                                })
                            })
                        })
                    }
                }
                post("/p2p/receive") {
                    if (!call.authorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                    } else {
                        val accepted = runCatching {
                            P2pReceiver.receive(Dependencies.db, settings ?: SettingsRepository(applicationContext), call.receiveText())
                        }.getOrDefault(0)
                        call.respond(HttpStatusCode.OK, mapOf("accepted" to accepted))
                    }
                }
            }
        }
        scope.launch { server?.start(wait = true) }
    }

    private fun startP2pServer(bindPort: Int) {
        p2pServer = embeddedServer(CIO, host = "0.0.0.0", port = bindPort) {
            routing {
                post("/p2p/receive") {
                    if (!call.authorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                    } else {
                        val accepted = runCatching {
                            P2pReceiver.receive(
                                Dependencies.db,
                                settings ?: SettingsRepository(applicationContext),
                                call.receiveText()
                            )
                        }.getOrDefault(0)
                        call.respond(HttpStatusCode.OK, mapOf("accepted" to accepted))
                    }
                }
            }
        }
        scope.launch { p2pServer?.start(wait = true) }
    }

    private suspend fun consumeRequests() {
        for (job in requests) {
            try {
                val messages = job.request["messages"]?.jsonArray.orEmpty()
                val prompt = messages.lastOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                val conversationId = Dependencies.db.dao().insertConversation(
                    ConversationEntity(title = "OpenAI API")
                )
                val result = StringBuilder()
                Dependencies.agent.send(conversationId, prompt).collect { event ->
                    when (event) {
                        is Agent.Event.Token -> {
                            result.append(event.text)
                            if (job.request["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
                                job.output.send(streamChunk(event.text))
                            }
                        }
                        is Agent.Event.Done -> if (result.isBlank()) result.append(event.full)
                        is Agent.Event.Failure -> throw IllegalStateException(event.message)
                        is Agent.Event.ToolRequired -> Unit
                    }
                }
                if (job.request["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != true) {
                    job.output.send(completion(result.toString()))
                }
                job.output.send(DONE)
            } catch (error: Exception) {
                job.output.trySend(errorChunk(error.message ?: "generation failed"))
                job.output.trySend(DONE)
            } finally {
                job.output.close()
                job.done.complete(Unit)
            }
        }
    }

    private fun ApplicationCall.authorized(): Boolean {
        val bearer = request.headers["Authorization"]?.removePrefix("Bearer ")
        val headerKey = request.headers["X-Aiia-Key"]
        return accessToken.isBlank() || bearer == accessToken || headerKey == accessToken ||
            (syncKey.isNotBlank() && (headerKey == syncKey || bearer == syncKey))
    }

    private fun streamChunk(text: String): String = buildJsonObject {
        put("id", JsonPrimitive(UUID.randomUUID().toString()))
        put("object", JsonPrimitive("chat.completion.chunk"))
        put("choices", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("delta", buildJsonObject { put("content", JsonPrimitive(text)) })
                put("finish_reason", JsonNull)
            })
        })
    }.toString()

    private fun completion(text: String): String = buildJsonObject {
        put("id", JsonPrimitive(UUID.randomUUID().toString()))
        put("object", JsonPrimitive("chat.completion"))
        put("choices", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("message", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(text))
                })
                put("finish_reason", JsonPrimitive("stop"))
            })
        })
    }.toString()

    private fun errorChunk(message: String): String = buildJsonObject {
        put("error", buildJsonObject { put("message", JsonPrimitive(message)) })
    }.toString()

    override fun onDestroy() {
        server?.stop(100, 1000)
        p2pServer?.stop(100, 1000)
        requests.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "AIIA API", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("com.aiia.app.ui.MainActivity")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AIIA Local API")
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "aiia_api"
        private const val NOTIFICATION_ID = 3001
        private const val DONE = "__DONE__"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ApiServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApiServerService::class.java))
        }
    }
}

private data class ApiJob(
    val request: JsonObject,
    val output: Channel<String>,
    val done: CompletableDeferred<Unit>
)


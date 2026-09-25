package com.aiia.app.plugins.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.aiia.app.plugins.engine.PluginManifest
import com.aiia.app.plugins.engine.PluginTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PluginSandboxClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val requestIds = AtomicInteger(1)
    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
            requests.values.forEach { it.completeExceptionally(IllegalStateException("Plugin sandbox disconnected")) }
            requests.clear()
        }
    }

    suspend fun install(file: File, manifest: PluginManifest): List<PluginTool> {
        val result = request(PluginSandboxService.MSG_INSTALL, Bundle().apply {
            putString(PluginSandboxService.KEY_PATH, file.absolutePath)
            putString(PluginSandboxService.KEY_ID, manifest.id)
            putString(PluginSandboxService.KEY_MANIFEST, json.encodeToString(PluginManifest.serializer(), manifest))
        })
        val root = result.jsonObject
        require(root["ok"]?.jsonPrimitive?.content == "true") {
            root["error"]?.jsonPrimitive?.content ?: "Plugin sandbox install failed"
        }
        return parseTools(root["tools"]?.jsonArray ?: JsonArray(emptyList()))
    }

    suspend fun call(id: String, tool: String, arguments: JsonObject): String {
        val result = request(PluginSandboxService.MSG_CALL, Bundle().apply {
            putString(PluginSandboxService.KEY_ID, id)
            putString(PluginSandboxService.KEY_TOOL, tool)
            putString(PluginSandboxService.KEY_ARGUMENTS, arguments.toString())
        }).jsonObject
        require(result["ok"]?.jsonPrimitive?.content == "true") {
            result["error"]?.jsonPrimitive?.content ?: "Plugin sandbox call failed"
        }
        return result["output"]?.jsonPrimitive?.content.orEmpty()
    }

    suspend fun tools(id: String): List<PluginTool> {
        val result = request(PluginSandboxService.MSG_LIST, Bundle().apply {
            putString(PluginSandboxService.KEY_ID, id)
        }).jsonObject
        require(result["ok"]?.jsonPrimitive?.content == "true") {
            result["error"]?.jsonPrimitive?.content ?: "Plugin sandbox list failed"
        }
        return parseTools(result["tools"]?.jsonArray ?: JsonArray(emptyList()))
    }

    suspend fun unload(id: String) {
        runCatching {
            request(PluginSandboxService.MSG_UNLOAD, Bundle().apply {
                putString(PluginSandboxService.KEY_ID, id)
            })
        }
    }

    private suspend fun request(what: Int, data: Bundle): JsonObject = withTimeout(15_000) {
        if (!bound) {
            appContext.bindService(
                Intent(appContext, PluginSandboxService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (!bound && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }
        }
        val messenger = remote ?: error("Plugin sandbox is unavailable")
        val id = requestIds.getAndIncrement()
        val deferred = CompletableDeferred<Bundle>()
        requests[id] = deferred
        try {
            messenger.send(Message.obtain(null, what).apply {
                arg1 = id
                this.data = data
                replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(message: Message) {
                        requests.remove(message.arg1)?.complete(message.data ?: Bundle())
                    }
                })
            })
            val raw = deferred.await().getString(PluginSandboxService.KEY_RESULT)
                ?: error("Empty plugin sandbox response")
            json.parseToJsonElement(raw).jsonObject
        } catch (error: RemoteException) {
            error("Plugin sandbox unavailable: ${error.message}")
        } finally {
            requests.remove(id)
        }
    }

    private fun parseTools(array: JsonArray): List<PluginTool> = array.mapNotNull { element ->
        val value = element.jsonObject
        PluginTool(
            name = value["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
            description = value["description"]?.jsonPrimitive?.content.orEmpty(),
            inputSchema = value["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
        )
    }

    override fun close() {
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        remote = null
    }

    companion object {
        const val MSG_INSTALL = 1
        const val MSG_CALL = 3
        const val MSG_LIST = 4
        const val MSG_UNLOAD = 5
        const val MSG_REPLY = 2
    }
}

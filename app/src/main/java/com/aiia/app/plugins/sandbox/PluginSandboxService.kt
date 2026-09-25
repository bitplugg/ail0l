package com.aiia.app.plugins.sandbox

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.aiia.app.plugins.engine.AiiaPlugin
import com.aiia.app.plugins.engine.PluginManifest
import com.aiia.app.plugins.engine.PluginTool
import dalvik.system.DexClassLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

class PluginSandboxService : Service() {
    private val json = Json { ignoreUnknownKeys = true }
    private val handler = Handler(Looper.getMainLooper()) { message -> handle(message); true }
    private val plugins = linkedMapOf<String, AiiaPlugin>()
    private val loaders = linkedMapOf<String, DexClassLoader>()

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    private fun handle(message: Message) {
        val reply = message.replyTo ?: return
        val data = message.data ?: Bundle()
        val result = runCatching {
            when (message.what) {
                PluginSandboxClient.MSG_INSTALL -> install(data)
                PluginSandboxClient.MSG_CALL -> call(data)
                PluginSandboxClient.MSG_LIST -> list(data.getString(KEY_ID).orEmpty())
                PluginSandboxClient.MSG_UNLOAD -> unload(data.getString(KEY_ID).orEmpty())
                else -> error("Unknown sandbox operation")
            }
        }.getOrElse { error ->
            buildJsonObject {
                put("ok", false)
                put("error", error.message ?: "sandbox failure")
            }
        }
        reply.send(Message.obtain(null, PluginSandboxClient.MSG_REPLY).apply {
            arg1 = message.arg1
            this.data = Bundle().apply {
                putString(KEY_RESULT, result.toString())
            }
        })
    }

    private fun install(data: Bundle): JsonObject {
        val path = data.getString(KEY_PATH) ?: error("Plugin path is missing")
        val id = data.getString(KEY_ID) ?: error("Plugin id is missing")
        val manifest = data.getString(KEY_MANIFEST)?.let {
            json.decodeFromString(PluginManifest.serializer(), it)
        } ?: error("Plugin manifest is missing")
        require(manifest.compatible()) { "Unsupported plugin API" }
        val optimized = File(codeCacheDir, "sandbox/$id").also { it.mkdirs() }
        val loader = DexClassLoader(path, optimized.absolutePath, null, javaClass.classLoader)
        val instance = loader.loadClass(manifest.entryClass).getDeclaredConstructor().newInstance()
        val plugin = instance as? AiiaPlugin ?: error("Plugin does not implement AiiaPlugin")
        plugins[id] = plugin
        loaders[id] = loader
        return buildJsonObject {
            put("ok", true)
            put("id", id)
            put("tools", toolsJson(plugin))
        }
    }

    private fun list(id: String): JsonObject {
        val plugin = plugins[id] ?: error("Plugin is not loaded")
        return buildJsonObject {
            put("ok", true)
            put("tools", toolsJson(plugin))
        }
    }

    private fun call(data: Bundle): JsonObject {
        val id = data.getString(KEY_ID) ?: error("Plugin id is missing")
        val tool = data.getString(KEY_TOOL) ?: error("Tool is missing")
        val arguments = data.getString(KEY_ARGUMENTS)?.let { json.decodeFromString(JsonObject.serializer(), it) }
            ?: error("Arguments are missing")
        val plugin = plugins[id] ?: error("Plugin is not loaded")
        val output = runBlocking { plugin.call(tool, arguments) }
        return buildJsonObject {
            put("ok", true)
            put("output", output)
        }
    }

    private fun unload(id: String): JsonObject {
        plugins.remove(id)
        loaders.remove(id)
        return buildJsonObject { put("ok", true) }
    }

    private fun toolsJson(plugin: AiiaPlugin): JsonArray = buildJsonArray {
        plugin.tools().forEach { tool ->
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", tool.inputSchema)
            })
        }
    }

    override fun onDestroy() {
        plugins.clear()
        loaders.clear()
        super.onDestroy()
    }

    companion object {
        const val MSG_INSTALL = 1
        const val MSG_CALL = 3
        const val MSG_LIST = 4
        const val MSG_UNLOAD = 5
        const val MSG_REPLY = 2
        const val KEY_PATH = "path"
        const val KEY_ID = "id"
        const val KEY_MANIFEST = "manifest"
        const val KEY_TOOL = "tool"
        const val KEY_ARGUMENTS = "arguments"
        const val KEY_RESULT = "result"
    }
}

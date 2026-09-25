package com.aiia.app.plugins.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLong

class StdioMcpTransport(
    private val serverName: String,
    command: List<String>
) : McpTransport {
    private val process = ProcessBuilder(command).redirectErrorStream(false).start()
    private val writer = PrintWriter(process.outputStream, true)
    private val reader = BufferedReader(InputStreamReader(process.inputStream))
    private val errorReader = BufferedReader(InputStreamReader(process.errorStream))
    private val ids = AtomicLong(1)
    private val lock = Any()

    init {
        Thread { errorReader.use { it.readText() } }.apply { isDaemon = true }.start()
    }

    override suspend fun initialize(): JsonObject = withContext(Dispatchers.IO) {
        rpcObject(McpJson.request(ids.getAndIncrement(), "initialize", buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", JsonPrimitive("AIIA")); put("version", JsonPrimitive("1.0")) })
        }))
    }

    override suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        val result = McpJson.resultObject(rpc(McpJson.request(ids.getAndIncrement(), "tools/list")))
        result["tools"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            McpTool(
                server = serverName,
                name = name,
                description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                inputSchema = obj["inputSchema"]?.jsonObject ?: buildJsonObject {}
            )
        }
    }

    override suspend fun callTool(name: String, arguments: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        rpc(McpJson.request(ids.getAndIncrement(), "tools/call", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", arguments)
        }))
    }

    private fun rpcObject(request: JsonObject): JsonObject = rpc(request).jsonObject

    private fun rpc(request: JsonObject): JsonElement = synchronized(lock) {
        writer.println(McpJson.format.encodeToString(JsonObject.serializer(), request))
        while (true) {
            val line = reader.readLine() ?: error("MCP process ended")
            if (line.isBlank()) continue
            val response = runCatching { McpJson.format.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val id = response["id"]?.jsonPrimitive?.content
            if (id == request["id"]?.jsonPrimitive?.content) return@synchronized response
        }
        error("unreachable")
    }

    override fun close() {
        runCatching { writer.close() }
        process.destroy()
    }
}

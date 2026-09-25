package com.aiia.app.plugins.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

class HttpMcpTransport(
    private val serverName: String,
    private val endpoint: String,
    private val client: HttpClient = HttpClient(CIO)
) : McpTransport {
    private val ids = AtomicLong(1)

    override suspend fun initialize(): JsonObject = call(
        McpJson.request(ids.getAndIncrement(), "initialize", buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", JsonPrimitive("AIIA")); put("version", JsonPrimitive("1.0")) })
        })
    )

    override suspend fun listTools(): List<McpTool> {
        val result = McpJson.resultObject(call(McpJson.request(ids.getAndIncrement(), "tools/list")))
        return result["tools"]?.let { tools ->
            tools.jsonArrayOrEmpty().mapNotNull { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                McpTool(serverName, name, obj["description"]?.jsonPrimitive?.content.orEmpty(), obj["inputSchema"]?.jsonObject ?: buildJsonObject {})
            }
        }.orEmpty()
    }

    override suspend fun callTool(name: String, arguments: JsonObject): JsonElement = call(
        McpJson.request(ids.getAndIncrement(), "tools/call", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", arguments)
        })
    )

    private suspend fun call(request: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val raw = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(McpJson.format.encodeToString(JsonObject.serializer(), request))
        }.bodyAsText()
        val payload = if (raw.contains("data:")) {
            raw.lineSequence().firstOrNull { it.trimStart().startsWith("data:") }
                ?.substringAfter("data:")?.trim().orEmpty()
        } else raw
        val element = McpJson.format.parseToJsonElement(payload)
        val obj = element.jsonObject
        if (obj["error"] != null) error("MCP error: ${obj["error"]}")
        obj
    }

    private fun JsonElement.jsonArrayOrEmpty() = runCatching { jsonArray }.getOrNull() ?: kotlinx.serialization.json.buildJsonArray {}

    override fun close() {
        client.close()
    }
}

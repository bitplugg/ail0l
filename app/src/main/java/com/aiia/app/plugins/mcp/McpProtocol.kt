package com.aiia.app.plugins.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class McpServerConfig(
    val name: String,
    val transport: String,
    val command: List<String> = emptyList(),
    val url: String = "",
    val enabled: Boolean = true
)

data class McpTool(
    val server: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject
) {
    fun prompt(): String = "$server/$name: $description ${inputSchema}"
}

interface McpTransport : AutoCloseable {
    suspend fun initialize(): JsonObject
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, arguments: JsonObject): JsonElement
}

internal object McpJson {
    val format = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun request(id: Long, method: String, params: JsonObject = buildJsonObject {}): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", JsonPrimitive(id))
        put("method", JsonPrimitive(method))
        put("params", params)
    }
    fun resultObject(element: JsonElement): JsonObject {
        val root = element.jsonObject
        return root["result"]?.jsonObject ?: root
    }
}

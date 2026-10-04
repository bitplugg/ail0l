package com.aiia.app.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

data class ToolCall(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    val raw: String = "",
    val mcpServer: String? = null,
    val mcpTool: String? = null,
    val mcpArguments: JsonObject? = null
) {
    val isMcp: Boolean get() = mcpServer != null && mcpTool != null
    val command: String get() = arguments["command"].orEmpty()
    val packageName: String get() = arguments["package"].orEmpty()
}

data class ToolResult(val success: Boolean, val output: String, val error: String? = null)

class ToolCallParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val tagPattern = Regex("<" + "tool_call>(.*?)</" + "tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val mcpTagPattern = Regex("<" + "mcp_call>(.*?)</" + "mcp_call>", RegexOption.DOT_MATCHES_ALL)
    private val fencePattern = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): ToolCall? {
        val mcpCandidate = mcpTagPattern.find(text)?.groupValues?.get(1)
        val candidate =
            mcpCandidate
                ?: tagPattern.find(text)?.groupValues?.get(1)
                ?: fencePattern.find(text)?.groupValues?.get(1)
                ?: text.trim().takeIf { it.startsWith("{") }
                ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(candidate).jsonObject
            val server = obj.string("server")
            val tool = obj.string("tool") ?: obj.string("name")
            if (mcpCandidate != null && server != null && tool != null) {
                ToolCall(
                    name = "mcp_call",
                    raw = candidate,
                    mcpServer = server,
                    mcpTool = tool,
                    mcpArguments = obj["arguments"] as? JsonObject ?: buildJsonObject {}
                )
            } else {
                tool?.let {
                    ToolCall(
                        it,
                        (obj["arguments"] as? JsonObject)?.stringArguments().orEmpty(),
                        candidate
                    )
                }
            }
        }.getOrNull()
    }

    /** A single object- or null-valued argument must not invalidate the whole tool call. */
    private fun JsonObject.stringArguments(): Map<String, String> = entries.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let { key to it }
    }.toMap()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

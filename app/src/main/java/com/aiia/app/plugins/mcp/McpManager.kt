package com.aiia.app.plugins.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class McpManager {
    private val transports = linkedMapOf<String, McpTransport>()
    private val _tools = MutableStateFlow<List<McpTool>>(emptyList())
    val tools: StateFlow<List<McpTool>> = _tools.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    suspend fun connect(configs: List<McpServerConfig>) {
        disconnect()
        configs.filter { it.enabled }.forEach { config ->
            runCatching {
                val transport = when (config.transport.lowercase()) {
                    "stdio" -> StdioMcpTransport(config.name, config.command)
                    "sse", "http", "https" -> HttpMcpTransport(config.name, config.url)
                    else -> error("Unknown MCP transport: ${config.transport}")
                }
                transport.initialize()
                transports[config.name] = transport
            }.onFailure { error ->
                _errors.value = _errors.value + (config.name to (error.message ?: "MCP connection failed"))
            }
        }
        refreshTools()
    }

    suspend fun refreshTools() {
        val all = mutableListOf<McpTool>()
        transports.forEach { (name, transport) ->
            runCatching { transport.listTools() }
                .onSuccess { all += it }
                .onFailure { _errors.value = _errors.value + (name to (it.message ?: "tools/list failed")) }
        }
        _tools.value = all
    }

    suspend fun call(server: String, tool: String, arguments: JsonObject = buildJsonObject {}) =
        transports[server]?.callTool(tool, arguments)

    fun disconnect() {
        transports.values.forEach { runCatching { it.close() } }
        transports.clear()
        _tools.value = emptyList()
    }

    fun systemPrompt(): String = tools.value.joinToString("\n") { "- ${it.prompt()}" }
}

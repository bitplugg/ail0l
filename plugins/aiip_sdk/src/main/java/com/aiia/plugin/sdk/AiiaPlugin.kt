package com.aiia.plugin.sdk

import kotlinx.serialization.json.JsonObject

interface AiiaPlugin {
    fun name(): String
    fun tools(): List<ToolDefinition>
    suspend fun call(name: String, arguments: JsonObject): String
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

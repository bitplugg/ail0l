package com.aiia.app.plugins.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PluginPermission(val name: String, val description: String = "")

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val entryClass: String,
    val permissions: List<PluginPermission> = emptyList(),
    val apiVersion: Int = 1
)

data class PluginTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

interface AiiaPlugin {
    val manifest: PluginManifest
    fun tools(): List<PluginTool>
    suspend fun call(name: String, arguments: JsonObject): String
}

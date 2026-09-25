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
    val apiVersion: Int = 1,
    val schemaVersion: Int = 1,
    val minApiVersion: Int = 1,
    val maxApiVersion: Int = 1
) {
    fun compatible(currentApiVersion: Int = PLUGIN_API_VERSION): Boolean =
        schemaVersion in 1..MAX_SUPPORTED_SCHEMA && currentApiVersion in minApiVersion..maxApiVersion

    companion object {
        const val PLUGIN_API_VERSION = 1
        const val MAX_SUPPORTED_SCHEMA = 2
    }
}

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

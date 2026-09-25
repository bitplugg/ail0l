package com.aiia.plugin.sdk

import kotlinx.serialization.Serializable

@Serializable
data class PluginPackageManifest(
    val id: String,
    val name: String,
    val version: String,
    val entryClass: String,
    val permissions: List<String> = emptyList(),
    val apiVersion: Int = 1,
    val schemaVersion: Int = 1,
    val minApiVersion: Int = 1,
    val maxApiVersion: Int = 1
) {
    fun compatible(currentApiVersion: Int = CURRENT_API_VERSION): Boolean =
        schemaVersion in 1..MAX_SCHEMA_VERSION && currentApiVersion in minApiVersion..maxApiVersion

    companion object {
        const val CURRENT_API_VERSION = 1
        const val MAX_SCHEMA_VERSION = 2
    }
}

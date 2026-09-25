package com.aiia.app.plugins.store

import kotlinx.serialization.Serializable

@Serializable
data class PluginStoreCatalog(
    val schema: Int = 1,
    val project: String = "AIIA Plugin Store",
    val repository: String = "https://github.com/bitplugg/aiia-plugin-store",
    val updated: String = "",
    val plugins: List<StorePlugin> = emptyList()
)

@Serializable
data class StorePlugin(
    val id: String,
    val slug: String,
    val format: String,
    val name: String,
    val version: String,
    val description: String,
    val tool: String,
    val entryClass: String,
    val artifact: String,
    val manifest: String? = null,
    val manifestSha256: String? = null,
    val source: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long = 0,
    val signerSha256: List<String> = emptyList()
)

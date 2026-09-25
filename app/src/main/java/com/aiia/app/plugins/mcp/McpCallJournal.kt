package com.aiia.app.plugins.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class McpCallRecord(
    val server: String,
    val tool: String,
    val output: String,
    val success: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

object McpCallJournal {
    private val _records = MutableStateFlow<List<McpCallRecord>>(emptyList())
    val records: StateFlow<List<McpCallRecord>> = _records.asStateFlow()

    fun record(server: String, tool: String, output: String, success: Boolean) {
        _records.value = (listOf(McpCallRecord(server, tool, output.take(8_000), success)) + _records.value).take(200)
    }

    fun clear() {
        _records.value = emptyList()
    }
}

package com.aiia.plugin.example

import com.aiia.plugin.sdk.AiiaPlugin
import com.aiia.plugin.sdk.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class ExamplePlugin : AiiaPlugin {
    override fun name(): String = "Example"
    override fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "echo",
            "Возвращает переданный текст",
            buildJsonObject { }
        )
    )
    override suspend fun call(name: String, arguments: JsonObject): String =
        if (name == "echo") arguments.toString() else "unknown tool"
}

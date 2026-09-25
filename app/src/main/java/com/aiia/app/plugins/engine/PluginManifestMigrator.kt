package com.aiia.app.plugins.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

object PluginManifestMigrator {
    fun migrate(raw: JsonObject): JsonObject {
        val values = raw.toMutableMap()
        if ("entryClass" !in values && "mainClass" in values) {
            values["entryClass"] = values.getValue("mainClass")
        }
        if ("apiVersion" !in values) values["apiVersion"] = JsonPrimitive(1)
        if ("schemaVersion" !in values) values["schemaVersion"] = JsonPrimitive(1)
        if ("minApiVersion" !in values) values["minApiVersion"] = JsonPrimitive(1)
        if ("maxApiVersion" !in values) values["maxApiVersion"] = JsonPrimitive(1)
        if ("permissions" !in values) values["permissions"] = buildJsonArray {}
        return JsonObject(values)
    }
}

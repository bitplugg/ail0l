package com.aiia.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

object Weather {

    private val json = Json { ignoreUnknownKeys = true }

    private val weatherCodes = mapOf(
        0 to "ясно",
        1 to "малооблачно",
        2 to "переменная облачность",
        3 to "облачно",
        45 to "туман",
        48 to "изморозь",
        51 to "мелкая морось",
        53 to "морось",
        55 to "сильная морось",
        61 to "небольшой дождь",
        63 to "дождь",
        65 to "сильный дождь",
        66 to "ледяной дождь",
        67 to "сильный ледяной дождь",
        71 to "небольшой снег",
        73 to "снег",
        75 to "сильный снег",
        77 to "снежинки",
        80 to "ливень",
        81 to "сильный ливень",
        82 to "очень сильный ливень",
        85 to "снегопады",
        86 to "сильные снегопады",
        95 to "гроза",
        96 to "гроза с градом",
        99 to "гроза с сильным градом"
    )

    suspend fun byCityText(text: String): String? = withContext(Dispatchers.IO) {
        val city = cityText(text) ?: return@withContext null
        val geo = geocode(city) ?: return@withContext null
        forecast(geo.first, geo.second, geo.third)
    }

    fun isAsking(text: String): Boolean {
        val lower = text.lowercase()
        return "погод" in lower && !lower.startsWith("найди в интернете:")
    }

    private suspend fun geocode(city: String): Triple<String, Double, Double>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${encode(city)}&count=1&language=ru&format=json"
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "AIIA/1.0").get().build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val root = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                val first = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@use null
                Triple(
                    first["name"]?.jsonPrimitive?.contentOrNull ?: city,
                    first["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@use null,
                    first["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@use null
                )
            }
        }.getOrNull()
    }

    private suspend fun forecast(city: String, lat: Double, lng: Double): String? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lng&current_weather=true&timezone=auto"
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "AIIA/1.0").get().build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val root = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                val cw = root["current_weather"]?.jsonObject ?: return@use null
                val temp = cw["temperature"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@use null
                val wind = cw["windspeed"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val code = cw["weathercode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val desc = weatherCodes[code] ?: "без осадков"
                "Погода: $city — ${temp.round1()}°C, $desc, ветер ${wind.round0()} км/ч"
            }
        }.getOrNull()
    }

    fun cityText(text: String): String? {
        val cleaned = text.lowercase().replace(Regex("[?!.,]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val idx = cleaned.indexOf("погод") ?: return null
        var rest = cleaned.substring(idx + "погод".length).trim()
        rest = rest.removePrefix("а")
            .removePrefix("у")
            .removePrefix("е")
        rest = rest.trim()
        val marker = listOf(" в ", " во ", " по ", " на ", ":").firstOrNull { rest.startsWith(it) }
            ?: return null
        val city = rest.substring(marker.length).trim()
        return city.takeIf { it.isNotEmpty() }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun Double.round1(): String = String.format("%.1f", this).replace(',', '.')
    private fun Double.round0(): String = String.format("%.0f", this)
}

package com.aiia.app.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object UsageLog {

    private const val PREFS = "usage"
    private const val KEY_COUNT = "count"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_MS = "ms"

    data class DayStats(val dateKey: String, val count: Int, val tokens: Long, val ms: Long)

    private const val MAX_DAYS = 90

    fun record(context: Context, tokens: Int, elapsedMs: Long) {
        if (tokens < 0) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = keyFor(System.currentTimeMillis())
        val editor = p.edit()
        editor.putLong("$key.$KEY_COUNT", p.getLong("$key.$KEY_COUNT", 0) + 1)
        editor.putLong("$key.$KEY_TOKENS", p.getLong("$key.$KEY_TOKENS", 0) + tokens)
        editor.putLong("$key.$KEY_MS", p.getLong("$key.$KEY_MS", 0) + elapsedMs)
        editor.apply()
        trim(context, p)
    }

    fun lastDays(context: Context, days: Int): List<DayStats> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = ArrayList<DayStats>()
        for (i in days - 1 downTo 0) {
            val at = System.currentTimeMillis() - i * 86_400_000L
            val key = keyFor(at)
            val count = p.getLong("$key.$KEY_COUNT", 0)
            val tokens = p.getLong("$key.$KEY_TOKENS", 0)
            val ms = p.getLong("$key.$KEY_MS", 0)
            if (count > 0) out.add(DayStats(key, count.toInt(), tokens, ms))
        }
        return out
    }

    fun today(context: Context): DayStats? = lastDays(context, 1).firstOrNull()

    private fun trim(context: Context, p: android.content.SharedPreferences) {
        val cutoff = System.currentTimeMillis() - MAX_DAYS * 86_400_000L
        val cutoffKey = keyFor(cutoff)
        val editor = p.edit()
        for (key in p.all.keys) {
            if (key.length == 10 && key < cutoffKey) editor.remove(key).let { }
            else if (key.contains(".") && key.substring(0, 10) < cutoffKey) editor.remove(key)
        }
        editor.apply()
    }

    private fun keyFor(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(at))
}

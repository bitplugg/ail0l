package com.aiia.app.util

/**
 * Pure text parsing for timers and reminders.
 *
 * Lives outside [Reminders] so it carries no Android dependencies and can be unit tested
 * without an emulator.
 */
object ReminderParser {
    data class Request(val triggerAt: Long, val text: String, val isTimer: Boolean)

    private val UNIT_STEMS =
        listOf(
            "секунд" to 1000L,
            "сек" to 1000L,
            "минут" to 60_000L,
            "мин" to 60_000L,
            "час" to 3_600_000L,
            "ден" to 86_400_000L,
            "дн" to 86_400_000L
        )

    /** [а-яё] instead of \w: Java's \w is ASCII-only, so "часа" used to parse as "час" + "а". */
    private const val UNIT_SUFFIX = "[а-яё]*"

    private val DURATION_PATTERN =
        Regex(
            """(\d+)\s*(секунд$UNIT_SUFFIX|сек$UNIT_SUFFIX|минут$UNIT_SUFFIX|мин$UNIT_SUFFIX|час$UNIT_SUFFIX|ден$UNIT_SUFFIX|дн$UNIT_SUFFIX)""",
            RegexOption.IGNORE_CASE
        )

    private val TIME_PATTERN =
        Regex("""(?:завтра\s+)?в\s+(\d{1,2})[:.](\d{2})""", RegexOption.IGNORE_CASE)

    private val TRAILING_DAY_MARKER = Regex("""[/]сегодня|[/]завтра""", RegexOption.IGNORE_CASE)

    private val WHITESPACE = Regex("""\s+""")

    private const val TRIGGER_WORD = "напомни"
    private const val DURATION_WORD = "через"

    private val TRIM_PREFIX_CHARS = charArrayOf(' ', ',', '—', '-', '.', ':')

    fun parse(text: String, now: Long = System.currentTimeMillis()): Request? {
        val t = text.trim().lowercase()
        if (t.isBlank()) return null

        val isTimerRequest = t.startsWith("таймер") || t.contains("поставь таймер")
        val isReminder = t.startsWith(TRIGGER_WORD) || t.contains("$TRIGGER_WORD мне") || t.startsWith("напоминание")
        if (!isTimerRequest && !isReminder) return null

        if (isTimerRequest) {
            val dur = parseDuration(t) ?: return null
            val label = cleanLabel(text, removePrefix = "таймер", removeWords = listOf("на"))
            return Request(now + dur.first, label.ifBlank { "Таймер на ${dur.second}" }, isTimer = true)
        }

        val trimmed = text.trim()
        val triggerAt = trimmed.indexOf(TRIGGER_WORD, ignoreCase = true)

        val dur = parseDuration(t)
        if (dur != null) {
            var body = if (triggerAt >= 0) trimmed.substring(triggerAt + TRIGGER_WORD.length) else trimmed
            val throughIdx = body.indexOf(DURATION_WORD, ignoreCase = true)
            if (throughIdx >= 0) body = body.substring(throughIdx + DURATION_WORD.length)
            val bodyCleaned = cleanDurationAndStopwords(body)
            return Request(
                triggerAt = now + dur.first,
                text = bodyCleaned.takeIf { it.isNotBlank() } ?: "Напоминание через ${dur.second}",
                isTimer = false
            )
        }

        val timeMatch = TIME_PATTERN.find(trimmed)
        if (timeMatch != null) {
            val hh = timeMatch.groupValues[1].toInt()
            val mm = timeMatch.groupValues[2].toInt()
            if (hh > 23 || mm > 59) return null
            val scheduled =
                java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    set(java.util.Calendar.HOUR_OF_DAY, hh)
                    set(java.util.Calendar.MINUTE, mm)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
            if (timeMatch.groupValues[0].contains("завтра", ignoreCase = true)) {
                scheduled.add(java.util.Calendar.DAY_OF_YEAR, 1)
            } else if (scheduled.timeInMillis <= now) {
                scheduled.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }

            val body =
                trimmed
                    .substring(timeMatch.range.last + 1)
                    .replace(TRAILING_DAY_MARKER, "")
                    .trimStart(*TRIM_PREFIX_CHARS)
            return Request(
                triggerAt = scheduled.timeInMillis,
                text = body.ifBlank { "Напоминание на %02d:%02d".format(hh, mm) },
                isTimer = false
            )
        }

        return null
    }

    private fun parseDuration(lowercaseText: String): Pair<Long, String>? {
        val matches = DURATION_PATTERN.findAll(lowercaseText).toList()
        if (matches.isEmpty()) return null
        var total = 0L
        val parts = ArrayList<String>(matches.size)
        for (match in matches) {
            val numStr = match.groupValues[1]
            val unit = match.groupValues[2].lowercase()
            val n = numStr.toLongOrNull() ?: continue
            val ms = unitMillis(unit) ?: continue
            total += n * ms
            parts.add("$numStr $unit")
        }
        if (total <= 0L) return null
        return total to parts.joinToString(" ")
    }

    private fun unitMillis(unit: String): Long? = UNIT_STEMS.firstOrNull { unit.startsWith(it.first) }?.second

    private fun cleanDurationAndStopwords(body: String): String {
        var s = DURATION_PATTERN.replace(body, " ")
        s = Regex("""$DURATION_WORD\s*""", RegexOption.IGNORE_CASE).replace(s, " ")
        return normalize(s)
    }

    private fun cleanLabel(text: String, removePrefix: String, removeWords: List<String>): String {
        var s = text.trim()
        if (s.lowercase().startsWith(removePrefix)) s = s.substring(removePrefix.length).trim()
        for (w in removeWords) {
            s = s.removePrefix("$w ").removePrefix(w).trim()
            s = if (s.startsWith(w)) s.removePrefix(w).trim() else s
        }
        s = DURATION_PATTERN.replace(s, " ")
        return normalize(s)
    }

    private fun normalize(value: String): String = WHITESPACE.replace(value, " ").trim().trimStart(*TRIM_PREFIX_CHARS).trim()
}

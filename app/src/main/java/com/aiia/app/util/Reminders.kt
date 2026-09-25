package com.aiia.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aiia.app.dm.Dependencies
import com.aiia.app.data.entities.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object Reminders {

    data class Request(val triggerAt: Long, val text: String, val isTimer: Boolean)

    fun parse(text: String, now: Long = System.currentTimeMillis()): Request? {
        val t = text.trim().lowercase()
        if (t.isBlank()) return null

        val isTimerRequest = t.startsWith("таймер") || t.contains("поставь таймер")
        val isReminder = t.startsWith("напомни") || t.contains("напомни мне") || t.startsWith("напоминание")
        if (!isTimerRequest && !isReminder) return null

        if (isTimerRequest) {
            val dur = parseDuration(t) ?: return null
            val label = cleanLabel(text, removePrefix = "таймер", removeWords = listOf("на"))
            return Request(now + dur.first, label.ifBlank { "Таймер на ${dur.second}" }, isTimer = true)
        }

        val trimmedLower = t.trim()

        val dur = parseDuration(trimmedLower)
        if (dur != null) {
            val reminderIdx = trimmedLower.indexOf("напомни")
            var body = if (reminderIdx >= 0) text.trim().substring(reminderIdx + "напомни".length) else text.trim()
            val cheresIdx = body.lowercase().indexOf("через")
            if (cheresIdx >= 0) body = body.substring(cheresIdx + "через".length)
            val bodyCleaned = cleanDurationAndStopwords(body, dur.first)
            return Request(now + dur.first, bodyCleaned.takeIf { it.isNotBlank() }
                ?: "Напоминание через ${dur.second}", isTimer = false)
        }

        val timeMatch = Regex("""(?:завтра\s+)?в\s+(\d{1,2})[:.](\d{2})""", RegexOption.IGNORE_CASE)
            .find(trimmedLower)
        if (timeMatch != null) {
            val hh = timeMatch.groupValues[1].toInt()
            val mm = timeMatch.groupValues[2].toInt()
            if (hh > 23 || mm > 59) return null
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val scheduled = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, hh)
                set(java.util.Calendar.MINUTE, mm)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (trimmedLower.contains("завтра")) scheduled.add(java.util.Calendar.DAY_OF_YEAR, 1)
            else if (scheduled.timeInMillis <= now) scheduled.add(java.util.Calendar.DAY_OF_YEAR, 1)

            val body = text.trim()
                .substring(timeMatch.range.last + 1)
                .replace(Regex("""[/]сегодня|[/]завтра"""), "")
                .trimStart(' ', ',', '—', '-', '.', ':').trim()
            return Request(
                scheduled.timeInMillis,
                body.ifBlank { "Напоминание на ${hh}:%02d".format(mm) },
                isTimer = false
            )
        }

        return null
    }

    private fun parseDuration(t: String): Pair<Long, String>? {
        val unitMs = mapOf(
            "секунд" to 1000L, "секунду" to 1000L, "секунды" to 1000L, "сек" to 1000L,
            "минут" to 60_000L, "минуту" to 60_000L, "минуты" to 60_000L, "мин" to 60_000L,
            "час" to 3_600_000L, "часа" to 3_600_000L, "часов" to 3_600_000L,
            "день" to 86_400_000L, "дня" to 86_400_000L, "дней" to 86_400_000L
        )
        val m = Regex("""(\d+)\s*(секунд\w*|сек|минут\w*|мин|час\w*|день|дня|дней)""", RegexOption.IGNORE_CASE)
            .findAll(t).map { it.groupValues.let { g -> g[1] to g[2] } }.toList()
        if (m.isEmpty()) return null
        var total = 0L
        val parts = ArrayList<String>()
        for ((numStr, unit) in m) {
            val n = numStr.toLongOrNull() ?: continue
            val ms = unitMs[unit.lowercase()] ?: continue
            total += n * ms
            parts.add("$numStr $unit")
        }
        if (total <= 0L) return null
        return total to parts.joinToString(" ")
    }

    private fun cleanDurationAndStopwords(body: String, _dur: Long): String {
        var s = body
        s = Regex("""\d+\s*(секунд\w*|сек|минут\w*|мин|час\w*|день|дня|дней)""", RegexOption.IGNORE_CASE)
            .replace(s, " ")
        s = Regex("""через\s*""", RegexOption.IGNORE_CASE).replace(s, " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
            .trimStart(' ', ',', '—', '-', '.', ':').trim()
        return s
    }

    private fun cleanLabel(text: String, removePrefix: String, removeWords: List<String>): String {
        var s = text.trim()
        if (s.lowercase().startsWith(removePrefix)) s = s.substring(removePrefix.length).trim()
        for (w in removeWords) s = s.removePrefix("$w ").removePrefix(w).trim().let { if (it.startsWith(w)) it.removePrefix(w).trim() else it }
        s = Regex("""\d+\s*(секунд\w*|сек|минут\w*|мин|час\w*|день|дня|дней)""", RegexOption.IGNORE_CASE).replace(s, " ")
        return s.replace(Regex("""\s+"""), " ").trim().trimStart(' ', ',', '—', '-', ':').trim()
    }

    suspend fun schedule(context: Context, req: Request): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        Notifications.ensureReminderChannel(context)
        val dao = Dependencies.db.dao()
        val id = dao.insertReminder(
            ReminderEntity(
                text = req.text.take(200),
                triggerAt = req.triggerAt,
                isTimer = req.isTimer
            )
        )
        if (id > 0L) arm(context, ReminderEntity(id, req.text.take(200), req.triggerAt, req.isTimer))
        id
    }

    fun arm(context: Context, r: ReminderEntity) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(context, r.id, r.isTimer)
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context, id: Long, isTimer: Boolean) {
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pending(context, id, isTimer))
    }

    private fun pending(context: Context, id: Long, isTimer: Boolean): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = Reminders.ACTION
            putExtra(Reminders.EXTRA_ID, id)
            putExtra(Reminders.EXTRA_IS_TIMER, isTimer)
        }
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun rescheduleAll(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val list = Dependencies.db.dao().upcomingReminders()
                for (r in list) arm(context, r)
            }
        }
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val id = intent.getLongExtra(EXTRA_ID, 0L)
            val isTimer = intent.getBooleanExtra(EXTRA_IS_TIMER, false)
            val text = runCatching {
                runBlocking { Dependencies.db.dao().reminderById(id)?.text }
            }.getOrNull().orEmpty()

            Notifications.ensureReminderChannel(context)
            val title = if (isTimer) "⏱ Таймер истёк" else "⏰ Напоминание"
            val pi = PendingIntent.getActivity(
                context, (id % Int.MAX_VALUE).toInt(),
                Intent(context, com.aiia.app.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, Notifications.REMINDER_CHANNEL_ID)
                .setSmallIcon(com.aiia.app.R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text.ifBlank { "Пришло время!" })
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text.ifBlank { "Пришло время!" }))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.notify((Notifications.REMINDER_NOTIF_ID + id.toInt()) % 100000, notification)

            runCatching {
                runBlocking { Dependencies.db.dao().deleteReminder(id) }
            }
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarm?.cancel(pending(context, id, isTimer))
        }
    }

    const val ACTION = "com.aiia.app.action.REMINDER"
    const val EXTRA_ID = "extra.id"
    const val EXTRA_IS_TIMER = "extra.is_timer"
}

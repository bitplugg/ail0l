package com.aiia.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aiia.app.data.entities.ReminderEntity
import com.aiia.app.dm.Dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object Reminders {
    data class Request(val triggerAt: Long, val text: String, val isTimer: Boolean)

    fun parse(text: String, now: Long = System.currentTimeMillis()): Request? =
        ReminderParser.parse(text, now)?.let { Request(it.triggerAt, it.text, it.isTimer) }

    suspend fun schedule(context: Context, req: Request): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        Notifications.ensureReminderChannel(context)
        val dao = Dependencies.db.dao()
        val id =
            dao.insertReminder(
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
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                action = Reminders.ACTION
                putExtra(Reminders.EXTRA_ID, id)
                putExtra(Reminders.EXTRA_IS_TIMER, isTimer)
            }
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
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
            val text =
                runCatching {
                    runBlocking { Dependencies.db.dao().reminderById(id)?.text }
                }.getOrNull().orEmpty()

            Notifications.ensureReminderChannel(context)
            val title = if (isTimer) "⏱ Таймер истёк" else "⏰ Напоминание"
            val pi =
                PendingIntent.getActivity(
                    context,
                    (id % Int.MAX_VALUE).toInt(),
                    Intent(context, com.aiia.app.ui.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            val notification =
                androidx.core.app.NotificationCompat.Builder(context, Notifications.REMINDER_CHANNEL_ID)
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

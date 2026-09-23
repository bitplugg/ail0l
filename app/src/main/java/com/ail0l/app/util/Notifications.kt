package com.ail0l.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ail0l.app.R
import com.ail0l.app.ui.MainActivity

/** Канал и рассылка push-уведомлений о входящих сетевых сообщениях. */
object Notifications {
    const val CHANNEL_ID = "ail0l_incoming"
    private const val NOTIF_ID = 1001

    const val MODEL_CHANNEL_ID = "ail0l_model"
    private const val MODEL_NOTIF_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Входящие сообщения AIL0L",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Уведомления о новых сообщениях из сетевого канала"
                }
            )
        }
    }

    /** Канал для прогресса скачивания моделей. */
    fun ensureModelChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(MODEL_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    MODEL_CHANNEL_ID,
                    "Скачивание моделей",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Прогресс загрузки ИИ-моделей"
                }
            )
        }
    }

    /** Постоянное уведомление о ходе скачивания модели. */
    fun notifyModelProgress(
        context: Context,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        if (totalBytes <= 0) return
        val pct = (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
        val mbytes = downloadedBytes / (1024.0 * 1024.0)
        val totalMb = totalBytes / (1024.0 * 1024.0)

        val notification = NotificationCompat.Builder(context, MODEL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Скачивание модели AIL0L")
            .setContentText("%d%% · %.0f из %.0f МБ".format(pct, mbytes, totalMb))
            .setProgress(100, pct, false)
            .setOnlyAlertOnce(true)
            .setOngoing(pct < 100)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(MODEL_NOTIF_ID, notification)
    }

    /** Снимает уведомление о ходе скачивания модели. */
    fun cancelModelProgress(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(MODEL_NOTIF_ID)
    }

    /** Показывает уведомление. Возвращает false, если нет разрешения (Android 13+). */
    fun notifyIncoming(context: Context, sender: String, body: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification)
        return true
    }
}
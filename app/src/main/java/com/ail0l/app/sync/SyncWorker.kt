package com.ail0l.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ail0l.app.data.AppDatabase
import com.ail0l.app.data.SettingsRepository
import com.ail0l.app.util.Notifications
import kotlinx.coroutines.flow.first

/** Периодически синхронизирует outbox/inbox с сетевым каналом и уведомляет о входящих. */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val settingsRepo = SettingsRepository(applicationContext)
        val settings = settingsRepo.settings.first()
        val report = SyncCoordinator(db, settingsRepo).syncNow()

        if (report.pulled > 0 && settings.notifyEnabled) {
            db.dao().unreadInbox().firstOrNull()?.let { latest ->
                Notifications.notifyIncoming(
                    applicationContext,
                    latest.sender.ifBlank { "AIL0L" },
                    latest.content
                )
                db.dao().markInboxRead(listOf(latest.id))
            }
        }

        // порядок: подчистить архив отправленных старше 7 дней
        runCatching { db.dao().pruneOutbox(System.currentTimeMillis() - OUTBOX_TTL_MS) }

        return if (report.error == null) Result.success() else Result.retry()
    }

    companion object {
        private const val OUTBOX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
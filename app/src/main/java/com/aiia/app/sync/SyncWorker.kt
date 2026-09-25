package com.aiia.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.util.Notifications
import kotlinx.coroutines.flow.first

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val settingsRepo = SettingsRepository(applicationContext)
        val settings = settingsRepo.settings.first()
        val report = SyncCoordinator(db, settingsRepo, applicationContext).syncNow()

        if (report.pulled > 0 && settings.notifyEnabled) {
            db.dao().unreadInbox().firstOrNull()?.let { latest ->
                Notifications.notifyIncoming(
                    applicationContext,
                    latest.sender.ifBlank { "AIIA" },
                    latest.content
                )
                db.dao().markInboxRead(listOf(latest.id))
            }
        }

        runCatching { db.dao().pruneOutbox(System.currentTimeMillis() - OUTBOX_TTL_MS) }

        return if (report.error == null) Result.success() else Result.retry()
    }

    companion object {
        private const val OUTBOX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

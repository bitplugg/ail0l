package com.ail0l.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Запускает разовую синхронизацию при изменении сети. */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.net.ConnectivityManager.CONNECTIVITY_ACTION) {
            SyncScheduler.touch(context)
        }
    }
}

/** Планирование фоновой синхронизации (WorkManager). */
object SyncScheduler {

    private const val WORK_NAME = "ail0l-sync"

    fun schedule(context: Context, intervalMinutes: Int) {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        val request = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes.coerceAtLeast(15).toLong(),
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Немедленная синхронизация (при подключении сети / запуске) */
    fun touch(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "ail0l-sync-now",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<SyncWorker>().build()
            )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
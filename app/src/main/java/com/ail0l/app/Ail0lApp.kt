package com.ail0l.app

import android.app.Application
import android.os.Build
import com.ail0l.app.dm.Dependencies
import com.ail0l.app.sync.SyncScheduler
import com.ail0l.app.util.Notifications
import com.ail0l.app.util.uniqueId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class Ail0lApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Dependencies.init(this)

        Notifications.ensureChannel(this)
        Notifications.ensureModelChannel(this)

        scope.launch {
            val settings = Dependencies.settings.settings.first()

            // стабильный ID устройства для сетевого канала
            if (settings.syncDeviceId.isBlank()) {
                Dependencies.settings.setAntenna(
                    deviceId = uniqueId().take(12),
                    deviceName = Build.MODEL.ifBlank { "AIL0L" },
                    password = settings.syncPassword
                )
            }

            // автоочистка устаревших фактов (старше месяца, кроме избранных)
            runCatching {
                val removed = Dependencies.db.dao()
                    .deleteOldFacts(System.currentTimeMillis() - FACTS_TTL_MS)
                if (removed > 0) {
                    android.util.Log.i("AIL0L", "Auto-cleaned $removed stale facts")
                }
            }

            // автопроводка: подбор и скачивание модели под устройство
            Dependencies.provision.provisionOrEnsure()

            // фоновая синхронизация по расписанию
            if (settings.syncEnabled && settings.syncBaseUrl.isNotBlank()) {
                SyncScheduler.schedule(applicationContext, settings.syncPollMinutes)
            }
        }
    }

    companion object {
        private const val FACTS_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 дней
    }
}
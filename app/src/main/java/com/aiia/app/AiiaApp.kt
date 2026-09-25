package com.aiia.app

import android.app.Application
import android.os.Build
import com.aiia.app.dm.Dependencies
import com.aiia.app.sync.SyncScheduler
import com.aiia.app.plugins.mcp.McpServerConfig
import com.aiia.app.util.Notifications
import com.aiia.app.util.uniqueId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AiiaApp : Application() {
    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Dependencies.init(this)

        Notifications.ensureChannel(this)
        Notifications.ensureModelChannel(this)

        scope.launch {
            val settings = Dependencies.settings.settings.first()

            runCatching { Dependencies.personas.ensureDefault() }
            runCatching { Dependencies.plugins.hotReload() }
            runCatching {
                val configs = json.decodeFromString<List<McpServerConfig>>(settings.mcpServersJson)
                Dependencies.mcp.connect(configs)
            }

            if (settings.syncDeviceId.isBlank()) {
                Dependencies.settings.setAntenna(
                    deviceId = uniqueId().take(12),
                    deviceName = Build.MODEL.ifBlank { "AIIA" },
                    password = settings.syncPassword
                )
            }

            runCatching {
                val removed = Dependencies.db.dao()
                    .deleteOldFacts(System.currentTimeMillis() - FACTS_TTL_MS)
                if (removed > 0) {
                    android.util.Log.i("AIIA", "Auto-cleaned $removed stale facts")
                }
            }

            Dependencies.provision.provisionOrEnsure()
            if (settings.ragEnabled) runCatching { Dependencies.vectorSearch.ensureIndexed() }

            if (settings.apiEnabled || settings.p2pEnabled) {
                com.aiia.app.api.ApiServerService.start(applicationContext)
            }

            if (settings.syncEnabled && (settings.syncBaseUrl.isNotBlank() || settings.p2pEnabled)) {
                SyncScheduler.schedule(applicationContext, settings.syncPollMinutes)
            }
        }
    }

    companion object {
        private const val FACTS_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}

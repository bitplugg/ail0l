package com.aiia.app.ai.download

import android.content.Context
import com.aiia.app.ai.Engine
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class ProvisionState(
    val entry: CatalogEntry? = null,
    val progress: DownloadProgress? = null,
    val status: String = "idle"
)

class ProvisionManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ProvisionState())
    val state: StateFlow<ProvisionState> = _state.asStateFlow()

    fun recommended(): CatalogEntry = ModelCatalog.recommend(context)

    fun modelsDir(): File = HfDownloader(context, db, "").modelsDir()

    fun localFile(entry: CatalogEntry): File = HfDownloader(context, db, "").localPath(entry)

    fun deviceSummary(): String =
        "ABI ${DeviceProfile.abi()} · ${DeviceProfile.cores()} ядер(а) · " +
            "RAM ${DeviceProfile.ramBytes(context) / (1024 * 1024 * 1024)} ГБ · " +
            "Android ${DeviceProfile.androidVersion()}"

    fun provisionOrEnsure() {
        scope.launch {
            val installed = db.dao().installedModel()
            if (installed != null) {
                val s = settings.settings.first()
                if (s.localModelPath.isBlank()) {
                    settings.setEngine(Engine.LOCAL)
                    settings.setLocalModelPath(installed.modelFile)
                }
            } else {
                download(recommended())
            }
        }
    }

    fun download(entry: CatalogEntry) {
        scope.launch {
            if (_state.value.status == "downloading") return@launch
            _state.value = ProvisionState(entry = entry, status = "downloading")
            Notifications.ensureModelChannel(context)
            Notifications.notifyModelProgress(context, 0, entry.sizeBytes)
            try {
                val token = settings.settings.first().hfToken
                val downloader = HfDownloader(context, db, token)
                downloader.download(entry).collect { p ->
                    _state.value = ProvisionState(
                        entry = entry,
                        progress = p,
                        status = if (p.done) "done" else "downloading"
                    )
                    Notifications.notifyModelProgress(context, p.downloadedBytes, p.totalBytes)
                }
                settings.setEngine(Engine.LOCAL)
                settings.setLocalModelPath(
                    File(
                        downloader.modelsDir(),
                        "${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf"
                    ).absolutePath
                )
                Notifications.cancelModelProgress(context)
            } catch (e: Exception) {
                Notifications.cancelModelProgress(context)
                _state.value = ProvisionState(entry = entry, status = "error")
            }
        }
    }
}

package com.ail0l.app.ai.download

import android.content.Context
import com.ail0l.app.ai.Engine
import com.ail0l.app.data.AppDatabase
import com.ail0l.app.data.SettingsRepository
import com.ail0l.app.util.Notifications
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
    val status: String = "idle" // idle | downloading | done | error
)

/**
 * Автопроводка устройства: подбирает модель по параметрам устройства
 * (ОЗУ, ABI, ядра) и скачивает её с Hugging Face, если ещё не установлена.
 * Прогресс доступен UI через [state].
 */
class ProvisionManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ProvisionState())
    val state: StateFlow<ProvisionState> = _state.asStateFlow()

    fun recommended(): CatalogEntry = ModelCatalog.recommend(context)

    /** Директория, куда складываются модели (как у [HfDownloader]) */
    fun modelsDir(): File = HfDownloader(context, db, "").modelsDir()

    fun localFile(entry: CatalogEntry): File = HfDownloader(context, db, "").localPath(entry)

    fun deviceSummary(): String =
        "ABI ${DeviceProfile.abi()} · ${DeviceProfile.cores()} ядер(а) · " +
            "RAM ${DeviceProfile.ramBytes(context) / (1024 * 1024 * 1024)} ГБ · " +
            "Android ${DeviceProfile.androidVersion()}"

    /** Проверяет установленную модель и связывает её с настройками; при отсутствии — качает рекомендуемую. */
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
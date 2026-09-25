package com.aiia.app.ui.screens.models

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiia.app.ai.Engine
import com.aiia.app.ai.download.CatalogEntry
import com.aiia.app.ai.download.DownloadProgress
import com.aiia.app.ai.download.ModelCatalog
import com.aiia.app.ai.download.ProvisionState
import com.aiia.app.ai.models.CatalogModel
import com.aiia.app.ai.models.HuggingFaceModelsApi
import com.aiia.app.ai.models.ModelArtifact
import com.aiia.app.ai.models.ModelDownloadManager
import com.aiia.app.ai.models.ModelDownloadProgress
import com.aiia.app.data.entities.ModelEntity
import com.aiia.app.dm.Dependencies
import com.aiia.app.util.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelUi(
    val entry: CatalogEntry,
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: DownloadProgress? = null,
    val recommended: Boolean = false,
    val active: Boolean = false
)

class ModelsViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val provision = Dependencies.provision
    private var downloadManager: ModelDownloadManager? = null

    private val _query = MutableStateFlow("")
    private val _remoteModels = MutableStateFlow<List<CatalogModel>>(emptyList())
    private val _remoteSearching = MutableStateFlow(false)
    private val _remoteProgress = MutableStateFlow<ModelDownloadProgress?>(null)
    private val _remoteError = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    private var searchGeneration = 0L

    val query: StateFlow<String> = _query
    val remoteModels: StateFlow<List<CatalogModel>> = _remoteModels
    val remoteSearching: StateFlow<Boolean> = _remoteSearching
    val remoteProgress: StateFlow<ModelDownloadProgress?> = _remoteProgress
    val remoteError: StateFlow<String?> = _remoteError

    val deviceSummary: String = provision.deviceSummary()
    val recommendedEntry: CatalogEntry = provision.recommended()

    private val _refresh = MutableStateFlow(0)

    val installed: StateFlow<ModelEntity?> =
        combine(
            Dependencies.db.dao().observeInstalledModel(),
            _refresh
        ) { model, _ -> model }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val provisionState: StateFlow<ProvisionState> = provision.state

    val models: StateFlow<List<ModelUi>> =
        combine(installed, provisionState, _refresh) { inst, prov, _ ->
            val instPath = inst?.modelFile
            ModelCatalog.entries().map { entry ->
                val localDir = Dependencies.provision.modelsDir()
                val installedFlag = instPath != null &&
                    instPath.endsWith("${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf")
                ModelUi(
                    entry = entry,
                    installed = installedFlag || localDir.resolve(
                        "${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf"
                    ).isFile,
                    downloading = prov.status == "downloading" && prov.entry == entry,
                    progress = prov.progress,
                    recommended = entry == recommendedEntry,
                    active = instPath != null &&
                        instPath.endsWith("${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf")
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(value: String) {
        searchJob?.cancel()
        searchGeneration++
        _query.value = value
        _remoteModels.value = emptyList()
        _remoteError.value = null
        _remoteSearching.value = false
    }

    fun searchHuggingFace() {
        val query = _query.value.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        val generation = ++searchGeneration
        searchJob = viewModelScope.launch {
            _remoteSearching.value = true
            _remoteError.value = null
            _remoteModels.value = emptyList()
            try {
                val token = Dependencies.settings.settings.first().hfToken
                val result = HuggingFaceModelsApi(token = token).search(query)
                _remoteModels.value = result
                if (result.isEmpty()) {
                    _remoteError.value = "Hugging Face не вернул GGUF-файлы"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _remoteError.value = error.message ?: "Ошибка Hugging Face"
            } finally {
                if (generation == searchGeneration) _remoteSearching.value = false
            }
        }
    }

    fun downloadRemote(artifact: ModelArtifact) {
        viewModelScope.launch {
            val token = Dependencies.settings.settings.first().hfToken
            val manager = downloadManager ?: ModelDownloadManager(
                getApplication(),
                token
            ) { progress ->
                _remoteProgress.value = progress
                Notifications.ensureModelChannel(getApplication())
                Notifications.notifyModelProgress(getApplication(), progress.downloadedBytes, progress.totalBytes)
                if (progress.state == ModelDownloadProgress.State.COMPLETED) {
                    Notifications.cancelModelProgress(getApplication())
                }
            }
                .also { downloadManager = it }
            _remoteError.value = null
            runCatching {
                manager.download(artifact).collect { progress ->
                    _remoteProgress.value = progress
                    if (progress.state == ModelDownloadProgress.State.COMPLETED) {
                        val path = manager.destination(artifact).absolutePath
                        if (artifact.isVisionProjector) {
                            Dependencies.settings.setMmprojPath(path)
                        } else {
                            Dependencies.db.dao().upsertModel(
                                ModelEntity(
                                    repo = artifact.repository,
                                    filename = artifact.filename,
                                    family = artifact.repository.substringAfterLast('/'),
                                    paramsLabel = "",
                                    quant = artifact.quantization ?: "GGUF",
                                    sizeBytes = artifact.sizeBytes,
                                    modelFile = path,
                                    installed = true
                                )
                            )
                            Dependencies.settings.setEngine(Engine.LOCAL)
                            Dependencies.settings.setLocalModelPath(path)
                        }
                    }
                }
            }.onFailure { _remoteError.value = it.message ?: "Загрузка не удачена" }
        }
    }

    fun install(entry: CatalogEntry) {
        provision.download(entry)
    }

    fun selectLocal(entry: CatalogEntry) {
        viewModelScope.launch {
            val modelsDir = Dependencies.provision.modelsDir()
            val path = modelsDir.resolve(
                "${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf"
            ).absolutePath
            Dependencies.settings.setEngine(Engine.LOCAL)
            Dependencies.settings.setLocalModelPath(path)
            _refresh.value++
        }
    }

    fun delete(entry: CatalogEntry) {
        viewModelScope.launch {
            val dir = Dependencies.provision.modelsDir()
            val file = dir.resolve("${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf")
            runCatching { file.delete() }

            val dao = Dependencies.db.dao()
            val inst = runCatching { dao.installedModel() }.getOrNull()
            if (inst != null && inst.modelFile == file.absolutePath) {
                runCatching { dao.deleteModel(inst.id) }
            }

            val s = Dependencies.settings.settings.first()
            if (s.localModelPath == file.absolutePath) {
                Dependencies.settings.setEngine(Engine.LOCAL)
                Dependencies.settings.setLocalModelPath("")
            }
            _refresh.value++
        }
    }
}

package com.ail0l.app.ui.screens.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ail0l.app.ai.Engine
import com.ail0l.app.ai.download.CatalogEntry
import com.ail0l.app.ai.download.DownloadProgress
import com.ail0l.app.ai.download.ModelCatalog
import com.ail0l.app.ai.download.ProvisionState
import com.ail0l.app.ai.models.HfFile
import com.ail0l.app.ai.models.HfModel
import com.ail0l.app.ai.models.HfModelClient
import com.ail0l.app.data.entities.ModelEntity
import com.ail0l.app.dm.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val provision = Dependencies.provision
    private val client = HfModelClient()

    private val _refresh = MutableStateFlow(0)
    private val _query = MutableStateFlow("")
    private val _results = MutableStateFlow<List<HfModel>>(emptyList())
    private val _files = MutableStateFlow<List<HfFile>>(emptyList())
    private val _busy = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val query: StateFlow<String> = _query
    val searchResults: StateFlow<List<HfModel>> = _results
    val files: StateFlow<List<HfFile>> = _files
    val searchBusy: StateFlow<Boolean> = _busy
    val searchError: StateFlow<String?> = _error
    val deviceSummary: String = provision.deviceSummary()
    val recommendedEntry: CatalogEntry = provision.recommended()

    val installed: StateFlow<ModelEntity?> = combine(
        Dependencies.db.dao().observeInstalledModel(),
        _refresh
    ) { model, _ -> model }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val provisionState: StateFlow<ProvisionState> = provision.state

    val models: StateFlow<List<ModelUi>> = combine(installed, provisionState, _refresh) { inst, prov, _ ->
        val instPath = inst?.modelFile
        ModelCatalog.entries().map { entry ->
            val fileName = "${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf"
            val installedFlag = instPath != null && instPath.endsWith(fileName)
            ModelUi(
                entry = entry,
                installed = installedFlag || provision.modelsDir().resolve(fileName).isFile,
                downloading = prov.status == "downloading" && prov.entry == entry,
                progress = prov.progress,
                recommended = entry == recommendedEntry,
                active = installedFlag
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun search() {
        viewModelScope.launch {
            val q = _query.value.trim()
            if (q.isBlank()) {
                _results.value = emptyList()
                _files.value = emptyList()
                return@launch
            }
            _busy.value = true
            _error.value = null
            runCatching { client.search(q) }
                .onSuccess { _results.value = it }
                .onFailure { _error.value = it.message ?: "Ошибка поиска моделей" }
            _busy.value = false
        }
    }

    fun openModel(model: HfModel) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            runCatching { client.files(model.id) }
                .onSuccess { _files.value = it }
                .onFailure { _error.value = it.message ?: "Не удалось загрузить список GGUF-файлов" }
            _busy.value = false
        }
    }

    fun install(file: HfFile) {
        val entry = CatalogEntry(
            repo = file.repo,
            family = file.family,
            paramsLabel = file.paramsLabel,
            quant = file.quant,
            filename = file.filename,
            sizeBytes = file.sizeBytes,
            ramNeededBytes = file.sizeBytes.coerceAtLeast(256L * 1024L * 1024L)
        )
        install(entry)
    }

    fun install(entry: CatalogEntry) {
        provision.download(entry)
    }

    fun selectLocal(entry: CatalogEntry) {
        viewModelScope.launch {
            val fileName = "${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf"
            val path = provision.modelsDir().resolve(fileName).absolutePath
            Dependencies.settings.setEngine(Engine.LOCAL)
            Dependencies.settings.setLocalModelPath(path)
            _refresh.value += 1
        }
    }

    fun delete(entry: CatalogEntry) {
        viewModelScope.launch {
            val fileName = "${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf"
            val file = provision.modelsDir().resolve(fileName)
            runCatching { file.delete() }

            val dao = Dependencies.db.dao()
            val inst = runCatching { dao.installedModel() }.getOrNull()
            if (inst != null && inst.modelFile == file.absolutePath) {
                runCatching { dao.deleteModel(inst.id) }
            }

            val settings = Dependencies.settings.settings.first()
            if (settings.localModelPath == file.absolutePath) {
                Dependencies.settings.setEngine(Engine.LOCAL)
                Dependencies.settings.setLocalModelPath("")
            }
            _refresh.value += 1
        }
    }
}

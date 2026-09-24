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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

 data class ModelUi(val entry: CatalogEntry, val installed: Boolean = false, val downloading: Boolean = false, val progress: DownloadProgress? = null, val recommended: Boolean = false, val active: Boolean = false)

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
    val deviceSummary = provision.deviceSummary()
    val recommendedEntry = provision.recommended()
    val installed: StateFlow<ModelEntity?> = combine(Dependencies.db.dao().observeInstalledModel(), _refresh) { model, _ -> model }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val provisionState: StateFlow<ProvisionState> = provision.state
    val models: StateFlow<List<ModelUi>> = combine(installed, provisionState, _refresh) { inst, prov, _ ->
        val path = inst?.modelFile
        ModelCatalog.entries().map { e ->
            val filename = "${e.family}-${e.paramsLabel}-${e.quant}.gguf"
            ModelUi(e, filename == path?.substringAfterLast('/'), prov.status == "downloading" && prov.entry == e, prov.progress, e == recommendedEntry, filename == path?.substringAfterLast('/'))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun setQuery(value: String) { _query.value = value }
    fun search() { viewModelScope.launch { _busy.value = true; _error.value = null; runCatching { client.search(_query.value) }.onSuccess { _results.value = it }.onFailure { _error.value = it.message }; _busy.value = false } }
    fun openModel(model: HfModel) { viewModelScope.launch { _busy.value = true; _error.value = null; runCatching { client.files(model.id) }.onSuccess { _files.value = it }.onFailure { _error.value = it.message }; _busy.value = false } }
    fun install(file: HfFile) { provision.download(CatalogEntry(file.repo, file.repo.substringAfterLast('/'), "", file.quant, file.filename, file.sizeBytes, file.sizeBytes * 2)) }
    fun install(entry: CatalogEntry) = provision.download(entry)
    fun selectLocal(entry: CatalogEntry) { viewModelScope.launch { val path = provision.modelsDir().resolve("${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf").absolutePath; Dependencies.settings.setEngine(Engine.LOCAL); Dependencies.settings.setLocalModelPath(path); _refresh.value++ } }
    fun delete(entry: CatalogEntry) { viewModelScope.launch { val file = provision.modelsDir().resolve("${entry.family}-${entry.paramsLabel}-${entry.quant}.gguf"); file.delete(); val installed = Dependencies.db.dao().installedModel(); if (installed?.modelFile == file.absolutePath) Dependencies.db.dao().deleteModel(installed.id); if (Dependencies.settings.settings.first().localModelPath == file.absolutePath) Dependencies.settings.setLocalModelPath(""); _refresh.value++ } }
}

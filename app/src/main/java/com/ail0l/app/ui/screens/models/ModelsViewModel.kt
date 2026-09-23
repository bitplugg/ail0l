package com.ail0l.app.ui.screens.models

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ail0l.app.ai.Engine
import com.ail0l.app.ai.download.CatalogEntry
import com.ail0l.app.ai.download.DownloadProgress
import com.ail0l.app.ai.download.ModelCatalog
import com.ail0l.app.ai.download.ProvisionState
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

class ModelsViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val provision = Dependencies.provision

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

    fun install(entry: CatalogEntry) {
        provision.download(entry)
    }

    /** Пометить как активную локальную модель */
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

    /** Удалить модель с диска (и запись в БД, если она была установлена). */
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
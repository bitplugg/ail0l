package com.aiia.app.plugins.store

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiia.app.dm.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PluginStoreState(
    val catalog: PluginStoreCatalog? = null,
    val loading: Boolean = false,
    val downloading: String? = null,
    val error: String? = null,
    val notice: String? = null
)

class PluginStoreViewModel(app: Application) : AndroidViewModel(app) {
    private val client = PluginStoreClient()
    private val _state = MutableStateFlow(PluginStoreState())
    val state: StateFlow<PluginStoreState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { client.catalog() }
                .onSuccess { _state.value = _state.value.copy(catalog = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Catalog unavailable") }
        }
    }

    fun download(plugin: StorePlugin) {
        if (_state.value.downloading != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(downloading = plugin.slug, error = null, notice = null)
            runCatching {
                val directory = File(getApplication<Application>().filesDir, "plugin-store")
                val downloaded = client.download(plugin, directory)
                val installed = Dependencies.plugins.install(
                    downloaded.file,
                    plugin.sha256,
                    plugin.signerSha256,
                    plugin.manifestSha256
                )
                Triple(plugin, downloaded, installed)
            }.onSuccess { (plugin, downloaded, installed) ->
                _state.value = _state.value.copy(
                    downloading = null,
                    notice = when (installed) {
                        is com.aiia.app.plugins.engine.InstallState.AwaitingPermission ->
                            "Проверьте permissions и подтвердите установку"
                        is com.aiia.app.plugins.engine.InstallState.Installed ->
                            "${plugin.name} установлен · ${downloaded.sha256.take(12)}"
                        is com.aiia.app.plugins.engine.InstallState.Failed -> "Ошибка установки: ${installed.message}"
                        else -> null
                    }
                )
            }.onFailure {
                _state.value = _state.value.copy(downloading = null, error = it.message ?: "Download failed")
            }
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }
}

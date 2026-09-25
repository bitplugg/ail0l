package com.aiia.app.ui.settings

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiia.app.ai.Engine
import com.aiia.app.data.Settings
import com.aiia.app.dm.Dependencies
import com.aiia.app.api.ApiServerService
import com.aiia.app.terminal.TerminalMode
import com.aiia.app.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val repo = Dependencies.settings
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    val settings: StateFlow<Settings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings.DEFAULT)

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    val deviceSummary = Dependencies.provision.deviceSummary()

    fun setEngine(engine: Engine) = launch { repo.setEngine(engine) }
    fun setMistral(key: String, model: String) = launch { repo.setMistral(key, model) }
    fun setOpenAi(base: String, key: String, model: String) = launch { repo.setOpenAi(base, key, model) }
    fun setAnthropic(key: String, model: String) = launch { repo.setAnthropic(key, model) }
    fun setSampling(temp: Float, topK: Int, topP: Float, ctx: Int) =
        launch { repo.setSampling(temp, topK, topP, ctx) }
    fun setPersona(p: String) = launch {
        repo.setPersona(p)
        Dependencies.personas.updateDefaultPrompt(p)
    }
    fun setMemoryEnabled(v: Boolean) = launch { repo.setMemoryEnabled(v) }
    fun setAutoLearn(v: Boolean) = launch { repo.setAutoLearn(v) }
    fun setSync(enabled: Boolean, base: String, poll: Int) = launch {
        repo.setSync(enabled, base, poll)
        if (enabled && (base.isNotBlank() || settings.value.p2pEnabled)) {
            SyncScheduler.schedule(getApplication(), poll)
        } else {
            SyncScheduler.cancel(getApplication())
        }
    }
    fun setAntenna(id: String, pass: String) = launch {
        val name = settings.value.syncDeviceName.ifBlank { "AIIA" }
        repo.setAntenna(id, name, pass)
    }
    fun setDeviceName(name: String) = launch {
        repo.setAntenna(settings.value.syncDeviceId, name, settings.value.syncPassword)
    }
    fun setCompute(threads: Int, flash: Boolean) = launch { repo.setCompute(threads, flash) }
    fun setTts(v: Boolean) = launch { repo.setTtsEnabled(v) }
    fun setStt(v: Boolean) = launch { repo.setSttEnabled(v) }
    fun setNotify(v: Boolean) = launch { repo.setNotifyEnabled(v) }
    fun setContacts(json: String) = launch { repo.setContacts(json) }
    fun setSearch(url: String, key: String) = launch { repo.setSearch(url, key) }
    fun setHfToken(t: String) = launch { repo.setHfToken(t) }
    fun setSystemDark(v: String) = launch { repo.setSystemDark(v) }
    fun setPreloadModel(v: Boolean) = launch { repo.setPreloadModel(v) }
    fun setDynamicColor(v: Boolean) = launch { repo.setDynamicColor(v) }
    fun setAnimations(v: Boolean) = launch { repo.setAnimationsEnabled(v) }
    fun setTerminalFontSize(v: Int) = launch { repo.setTerminalFontSize(v) }
    fun setTerminalMode(mode: String) = launch { repo.setTerminalMode(mode) }
    fun setShizuku(v: Boolean) = launch { repo.setShizukuEnabled(v) }
    fun setRoot(v: Boolean) = launch { repo.setRootEnabled(v) }
    fun setConfirmTools(v: Boolean) = launch { repo.setConfirmToolCalls(v) }
    fun setRag(enabled: Boolean, path: String, dimensions: Int) = launch {
        repo.setRag(enabled, path, dimensions)
        if (enabled) Dependencies.vectorSearch.loadConfiguredModel()
    }
    fun setMmproj(path: String) = launch { repo.setMmprojPath(path) }
    fun setLora(path: String, scale: Float) = launch { repo.setLora(path, scale) }
    fun setKvCache(v: Boolean) = launch { repo.setKvCacheEnabled(v) }
    fun setMcp(value: String) = launch {
        repo.setMcpServers(value)
        runCatching {
            val configs = json.decodeFromString<List<com.aiia.app.plugins.mcp.McpServerConfig>>(value)
            Dependencies.mcp.connect(configs)
        }
    }

    fun setApi(enabled: Boolean, port: Int, token: String) = launch {
        repo.setApi(enabled, port, token)
        if (enabled) ApiServerService.start(getApplication()) else ApiServerService.stop(getApplication())
    }

    fun setP2p(enabled: Boolean, port: Int) = launch {
        repo.setP2p(enabled, port)
        if (enabled) {
            ApiServerService.start(getApplication())
            if (settings.value.syncEnabled) SyncScheduler.schedule(getApplication(), settings.value.syncPollMinutes)
        }
    }

    fun confirmPlugin() {
        viewModelScope.launch { Dependencies.plugins.confirmInstall() }
    }

    fun rejectPlugin() {
        Dependencies.plugins.rejectInstall()
    }

    fun reloadPlugins() {
        viewModelScope.launch { Dependencies.plugins.hotReload() }
    }

    fun reset() = launch { repo.reset() }

    fun syncNow() {
        viewModelScope.launch {
            _syncStatus.value = "Синхронизируем…"
            val report = Dependencies.syncCoordinator.syncNow()
            _syncStatus.value = if (report.error == null)
                "Готово: отправлено ${report.pushed}, получено ${report.pulled}"
            else "Ошибка: ${report.error}"
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

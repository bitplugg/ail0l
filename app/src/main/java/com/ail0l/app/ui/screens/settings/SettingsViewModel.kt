package com.ail0l.app.ui.screens.settings

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ail0l.app.ai.Engine
import com.ail0l.app.data.Settings
import com.ail0l.app.dm.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val repo = Dependencies.settings

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
    fun setPersona(p: String) = launch { repo.setPersona(p) }
    fun setMemoryEnabled(v: Boolean) = launch { repo.setMemoryEnabled(v) }
    fun setAutoLearn(v: Boolean) = launch { repo.setAutoLearn(v) }
    fun setSync(enabled: Boolean, base: String, poll: Int) =
        launch { repo.setSync(enabled, base, poll) }
    fun setAntenna(id: String, pass: String) = launch {
        val name = settings.value.syncDeviceName.ifBlank { "AIL0L" }
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

    private fun launch(block: suspend () -> Unit) =
        viewModelScope.launch { block() }
}
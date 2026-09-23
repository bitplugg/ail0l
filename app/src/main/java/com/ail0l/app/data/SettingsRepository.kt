package com.ail0l.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ail0l.app.ai.Engine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ail0l_settings")

data class Settings(
    val engine: Engine = Engine.LOCAL,

    val localModelPath: String = "",

    val mistralApiKey: String = "",
    val mistralModel: String = "mistral-small-latest",

    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val openAiApiKey: String = "",
    val openAiModel: String = "gpt-4o-mini",

    val anthropicApiKey: String = "",
    val anthropicModel: String = "claude-3-5-haiku-latest",

    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val contextLength: Int = 512,

    val cpuThreads: Int = 0,          // 0 = авто
    val flashAttention: Boolean = true,

    val persona: String = "Ты AIL0L — дружелюбный ИИ-напарник.",
    val memoryEnabled: Boolean = true,
    val autoLearnEnabled: Boolean = true,

    val ttsEnabled: Boolean = false,
    val sttEnabled: Boolean = true,
    val notifyEnabled: Boolean = true,

    val syncEnabled: Boolean = false,
    val syncBaseUrl: String = "",
    val syncPollMinutes: Int = 15,
    val syncDeviceId: String = "",
    val syncDeviceName: String = "",
    val syncPassword: String = "",
    val contactsJson: String = "{}",      // имя устройства -> deviceId

    val searchUrl: String = "",      // кастомный SearXNG/API для поиска в интернете
    val searchKey: String = "",

    val hfToken: String = "",

    val systemDark: String = "auto" // light | dark | auto
) {
    companion object {
        val DEFAULT = Settings()
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ENGINE = stringPreferencesKey("engine")

        val LOCAL_MODEL_PATH = stringPreferencesKey("local_model_path")

        val MISTRAL_KEY = stringPreferencesKey("mistral_api_key")
        val MISTRAL_MODEL = stringPreferencesKey("mistral_model")

        val OPENAI_BASE = stringPreferencesKey("openai_base_url")
        val OPENAI_KEY = stringPreferencesKey("openai_api_key")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")

        val ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key")
        val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")

        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_K = intPreferencesKey("top_k")
        val TOP_P = floatPreferencesKey("top_p")
        val CONTEXT_LENGTH = intPreferencesKey("context_length")

        val CPU_THREADS = intPreferencesKey("cpu_threads")
        val FLASH_ATTN = booleanPreferencesKey("flash_attn")

        val PERSONA = stringPreferencesKey("persona")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val AUTO_LEARN = booleanPreferencesKey("auto_learn")

        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val STT_ENABLED = booleanPreferencesKey("stt_enabled")
        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")

        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SYNC_BASE_URL = stringPreferencesKey("sync_base_url")
        val SYNC_POLL = intPreferencesKey("sync_poll_minutes")
        val SYNC_DEVICE_ID = stringPreferencesKey("sync_device_id")
        val SYNC_DEVICE_NAME = stringPreferencesKey("sync_device_name")
        val SYNC_PASSWORD = stringPreferencesKey("sync_password")
        val CONTACTS_JSON = stringPreferencesKey("contacts_json")

        val SEARCH_URL = stringPreferencesKey("search_url")
        val SEARCH_KEY = stringPreferencesKey("search_key")

        val HF_TOKEN = stringPreferencesKey("hf_token")

        val SYSTEM_DARK = stringPreferencesKey("system_dark")
    }

    private val store = context.dataStore

    val settings: Flow<Settings> = store.data.map { prefs ->
        Settings(
            engine = Engine.valueOf(prefs[Keys.ENGINE] ?: Engine.LOCAL.name),
            localModelPath = prefs[Keys.LOCAL_MODEL_PATH] ?: "",
            mistralApiKey = prefs[Keys.MISTRAL_KEY] ?: "",
            mistralModel = prefs[Keys.MISTRAL_MODEL] ?: Settings.DEFAULT.mistralModel,
            openAiBaseUrl = prefs[Keys.OPENAI_BASE] ?: Settings.DEFAULT.openAiBaseUrl,
            openAiApiKey = prefs[Keys.OPENAI_KEY] ?: "",
            openAiModel = prefs[Keys.OPENAI_MODEL] ?: Settings.DEFAULT.openAiModel,
            anthropicApiKey = prefs[Keys.ANTHROPIC_KEY] ?: "",
            anthropicModel = prefs[Keys.ANTHROPIC_MODEL] ?: Settings.DEFAULT.anthropicModel,
            temperature = prefs[Keys.TEMPERATURE] ?: Settings.DEFAULT.temperature,
            topK = prefs[Keys.TOP_K] ?: Settings.DEFAULT.topK,
            topP = prefs[Keys.TOP_P] ?: Settings.DEFAULT.topP,
            contextLength = prefs[Keys.CONTEXT_LENGTH] ?: Settings.DEFAULT.contextLength,
            cpuThreads = prefs[Keys.CPU_THREADS] ?: Settings.DEFAULT.cpuThreads,
            flashAttention = prefs[Keys.FLASH_ATTN] ?: Settings.DEFAULT.flashAttention,
            persona = prefs[Keys.PERSONA] ?: Settings.DEFAULT.persona,
            memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: Settings.DEFAULT.memoryEnabled,
            autoLearnEnabled = prefs[Keys.AUTO_LEARN] ?: Settings.DEFAULT.autoLearnEnabled,
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: Settings.DEFAULT.ttsEnabled,
            sttEnabled = prefs[Keys.STT_ENABLED] ?: Settings.DEFAULT.sttEnabled,
            notifyEnabled = prefs[Keys.NOTIFY_ENABLED] ?: Settings.DEFAULT.notifyEnabled,
            syncEnabled = prefs[Keys.SYNC_ENABLED] ?: Settings.DEFAULT.syncEnabled,
            syncBaseUrl = prefs[Keys.SYNC_BASE_URL] ?: "",
            syncPollMinutes = prefs[Keys.SYNC_POLL] ?: Settings.DEFAULT.syncPollMinutes,
            syncDeviceId = prefs[Keys.SYNC_DEVICE_ID] ?: "",
            syncDeviceName = prefs[Keys.SYNC_DEVICE_NAME] ?: "",
            syncPassword = prefs[Keys.SYNC_PASSWORD] ?: "",
            contactsJson = prefs[Keys.CONTACTS_JSON] ?: "{}",
            searchUrl = prefs[Keys.SEARCH_URL] ?: "",
            searchKey = prefs[Keys.SEARCH_KEY] ?: "",
            hfToken = prefs[Keys.HF_TOKEN] ?: "",
            systemDark = prefs[Keys.SYSTEM_DARK] ?: Settings.DEFAULT.systemDark
        )
    }

    suspend fun setEngine(engine: Engine) = store.edit { it[Keys.ENGINE] = engine.name }
    suspend fun setLocalModelPath(path: String) = store.edit { it[Keys.LOCAL_MODEL_PATH] = path }

    suspend fun setMistral(key: String, model: String) = store.edit {
        it[Keys.MISTRAL_KEY] = key
        it[Keys.MISTRAL_MODEL] = model
    }

    suspend fun setOpenAi(base: String, key: String, model: String) = store.edit {
        it[Keys.OPENAI_BASE] = base
        it[Keys.OPENAI_KEY] = key
        it[Keys.OPENAI_MODEL] = model
    }

    suspend fun setAnthropic(key: String, model: String) = store.edit {
        it[Keys.ANTHROPIC_KEY] = key
        it[Keys.ANTHROPIC_MODEL] = model
    }

    suspend fun setSampling(temperature: Float, topK: Int, topP: Float, contextLength: Int) =
        store.edit {
            it[Keys.TEMPERATURE] = temperature
            it[Keys.TOP_K] = topK
            it[Keys.TOP_P] = topP
            it[Keys.CONTEXT_LENGTH] = contextLength
        }

    suspend fun setCompute(threads: Int, flashAttn: Boolean) = store.edit {
        it[Keys.CPU_THREADS] = threads
        it[Keys.FLASH_ATTN] = flashAttn
    }

    suspend fun setPersona(p: String) = store.edit { it[Keys.PERSONA] = p }
    suspend fun setMemoryEnabled(v: Boolean) = store.edit { it[Keys.MEMORY_ENABLED] = v }
    suspend fun setAutoLearn(v: Boolean) = store.edit { it[Keys.AUTO_LEARN] = v }

    suspend fun setTtsEnabled(v: Boolean) = store.edit { it[Keys.TTS_ENABLED] = v }
    suspend fun setSttEnabled(v: Boolean) = store.edit { it[Keys.STT_ENABLED] = v }
    suspend fun setNotifyEnabled(v: Boolean) = store.edit { it[Keys.NOTIFY_ENABLED] = v }

    suspend fun setSync(enabled: Boolean, baseUrl: String = "", poll: Int = 15) = store.edit {
        it[Keys.SYNC_ENABLED] = enabled
        if (baseUrl.isNotBlank()) it[Keys.SYNC_BASE_URL] = baseUrl
        it[Keys.SYNC_POLL] = poll
    }

    suspend fun setAntenna(deviceId: String, deviceName: String, password: String) = store.edit {
        it[Keys.SYNC_DEVICE_ID] = deviceId
        it[Keys.SYNC_DEVICE_NAME] = deviceName
        it[Keys.SYNC_PASSWORD] = password
    }

    suspend fun setContacts(json: String) = store.edit { it[Keys.CONTACTS_JSON] = json }

    suspend fun setSearch(url: String, key: String) = store.edit {
        it[Keys.SEARCH_URL] = url
        it[Keys.SEARCH_KEY] = key
    }

    suspend fun setHfToken(token: String) = store.edit { it[Keys.HF_TOKEN] = token }
    suspend fun setSystemDark(v: String) = store.edit { it[Keys.SYSTEM_DARK] = v }

    /** Сброс всех настроек к значениям по умолчанию. */
    suspend fun reset() = store.edit { it.clear() }
}
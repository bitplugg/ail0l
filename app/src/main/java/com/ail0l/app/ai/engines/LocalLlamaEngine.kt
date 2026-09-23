package com.ail0l.app.ai.engines

import com.ail0l.app.ai.ChatMessage
import com.ail0l.app.data.Settings
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Локальный движок на основе llama.cpp (модуль :llama).
 *
 * Контекст нативного слоя полностью соответствует списку [ChatMessage],
 * который присылает агент: системный промпт (персона + факты из памяти +
 * итоги бесед) задаётся через [InferenceEngine.hydrateContext], а история —
 * из того же списка. Благодаря этому факты памяти видны и локальной модели,
 * а при переключении бесед контекст корректно перестраивается.
 */
class LocalLlamaEngine(
    private val engine: InferenceEngine,
    private val settings: Settings,
    private val nBatch: Int = DEFAULT_N_BATCH
) : AiEngine {

    private var loadedPath: String? = null
    private var nativeSystem: String? = null
    private val nativeThread = mutableListOf<Pair<String, String>>()
    private val mutex = Mutex()

    override val label: String
        get() = settings.localModelPath.substringAfterLast('/').ifBlank { "llama.cpp" }

    private val currentState: InferenceEngine.State
        get() = engine.state.value

    private suspend fun ensureLoaded() {
        val path = settings.localModelPath
        require(path.isNotBlank()) { "Не выбрана локальная модель" }

        if (loadedPath != path || !currentState.isModelLoaded) {
            withContext(Dispatchers.IO) {
                // ждём/восстанавливаем инициализацию нативного слоя
                engine.ensureInitialized()

                val f = File(path)
                require(f.exists() && f.isFile && f.canRead()) { "Файл модели недоступен: $path" }

                if (currentState.isModelLoaded) engine.cleanUp()

                engine.configure(
                    nCtx = settings.contextLength.coerceIn(128, 4096),
                    nThreads = settings.cpuThreads.coerceAtLeast(0),
                    flashAttn = settings.flashAttention,
                    nBatch = nBatch
                )
                engine.loadModel(path)
            }
        }
    }

    override fun chat(messages: List<ChatMessage>, predictLength: Int?): Flow<String> = flow {
        mutex.withLock {
            ensureLoaded()

            // семплер обновляется каждый ход — настройки могли измениться
            engine.setSamplerParams(settings.temperature, settings.topK, settings.topP)

            val system = (messages.firstOrNull { it is ChatMessage.System } as? ChatMessage.System)
                ?.content?.trim()?.ifBlank { null }
            val thread = messages
                .filterNot { it is ChatMessage.System }
                .map { it.role to it.content }
            val user = thread.lastOrNull()
                ?: throw IllegalArgumentException("Нет сообщения пользователя")
            val history = thread.dropLast(1)

            val wantedSystem = system ?: DEFAULT_PERSONA
            if (nativeSystem != wantedSystem || nativeThread != history) {
                engine.hydrateContext(
                    systemPrompt = wantedSystem,
                    roles = history.map { it.first },
                    contents = history.map { it.second }
                )
                nativeSystem = wantedSystem
                nativeThread.clear()
                nativeThread.addAll(history)
            }

            engine.sendUserPrompt(
                message = user.second,
                predictLength = predictLength ?: InferenceEngine.DEFAULT_PREDICT_LENGTH
            )
                .catch { e -> throw IllegalStateException("Ошибка генерации: ${e.message}", e) }
                .collect { token -> emit(token) }
            nativeThread += user
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun release() {
        mutex.withLock {
            if (currentState.isModelLoaded) {
                engine.cleanUp()
            }
            loadedPath = null
            nativeSystem = null
            nativeThread.clear()
        }
    }

    companion object {
        const val DEFAULT_PERSONA = "Ты AIL0L — дружелюбный ИИ-напарник."
        const val DEFAULT_N_BATCH = 512

        /** Автоподбор батча под объём ОЗУ: на 4 ГБ — 512, на 8 ГБ — 2048, потолок 2048. */
        fun autoBatchFor(ramBytes: Long): Int =
            ((ramBytes / (1024 * 1024 * 1024)) * 256)
                .toInt()
                .coerceIn(DEFAULT_N_BATCH, 2048)
    }
}
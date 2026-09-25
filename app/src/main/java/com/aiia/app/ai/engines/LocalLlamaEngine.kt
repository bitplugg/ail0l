package com.aiia.app.ai.engines

import com.aiia.app.ai.ChatMessage
import com.aiia.app.agent.ContextCacheManager
import com.aiia.app.data.Settings
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import android.util.Log
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

class LocalLlamaEngine(
    private val engine: InferenceEngine,
    private val settings: Settings,
    private val nBatch: Int = DEFAULT_N_BATCH,
    private val contextCache: ContextCacheManager? = null
) : AiEngine {

    private var loadedPath: String? = null
    private var nativeSystem: String? = null
    private var appliedMmproj: String? = null
    private var appliedLora: String? = null
    private var appliedLoraScale: Float? = null
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
                loadedPath = path
                appliedMmproj = null
                appliedLora = null
                appliedLoraScale = null
            }
        }
    }

    override fun chat(messages: List<ChatMessage>, predictLength: Int?): Flow<String> = flow {
        mutex.withLock {
            ensureLoaded()

            if (appliedMmproj != settings.mmprojPath) {
                runCatching { engine.setMmproj(settings.mmprojPath) }
                    .onSuccess { appliedMmproj = settings.mmprojPath }
                    .onFailure { Log.w("LocalLlamaEngine", "mmproj unavailable: ${it.message}") }
            }
            if (appliedLora != settings.loraPath || appliedLoraScale != settings.loraScale) {
                runCatching { engine.setLoraAdapter(settings.loraPath, settings.loraScale) }
                    .onSuccess {
                        appliedLora = settings.loraPath
                        appliedLoraScale = settings.loraScale
                    }
                    .onFailure { Log.w("LocalLlamaEngine", "LoRA unavailable: ${it.message}") }
            }

            engine.setSamplerParams(settings.temperature, settings.topK, settings.topP)

            val system = (messages.firstOrNull { it is ChatMessage.System } as? ChatMessage.System)
                ?.content?.trim()?.ifBlank { null }
            val rawThread = messages.filterNot { it is ChatMessage.System }
            val lastMessage = rawThread.lastOrNull()
                ?: throw IllegalArgumentException("Нет сообщения пользователя")
            val userMessage = lastMessage as? ChatMessage.User
            val visionPaths = userMessage?.images.orEmpty().mapNotNull { image ->
                image.uri.takeIf { File(it).isFile }
            }
            val visionPath = visionPaths.firstOrNull()
            val nativeVision = settings.mmprojPath.isNotBlank() && visionPath != null
            val visionContext = if (nativeVision) "" else visionPaths.mapNotNull { path ->
                runCatching {
                    "Изображение «${File(path).name}»: " +
                        engine.analyzeImage(path, "Опиши фото, распознай видимый текст и ответь на вопрос пользователя.")
                }.getOrNull()
            }.joinToString("\n")
            val userContent = if (visionContext.isBlank()) lastMessage.content
            else "${lastMessage.content}\n\n$visionContext"
            val thread = rawThread.mapIndexed { index, message ->
                message.role to if (index == rawThread.lastIndex) userContent else message.content
            }
            val user = thread.last()
            val history = thread.dropLast(1)

            val wantedSystem = system ?: DEFAULT_PERSONA
            if (nativeSystem != wantedSystem || nativeThread != history) {
                val key = contextCache?.let {
                    ContextCacheManager.Key(settings.localModelPath, wantedSystem, settings.contextLength)
                }
                val restored = if (settings.kvCacheEnabled && history.isEmpty() && key != null) {
                    contextCache?.restore(engine, key) == true
                } else false
                if (!restored) {
                    engine.hydrateContext(
                        systemPrompt = wantedSystem,
                        roles = history.map { it.first },
                        contents = history.map { it.second }
                    )
                }
                nativeSystem = wantedSystem
                nativeThread.clear()
                nativeThread.addAll(history)
                if (settings.kvCacheEnabled && key != null) contextCache?.save(engine, key)
            }

            val visionResult = if (nativeVision) {
                val builder = StringBuilder()
                engine.generateWithImage(
                    path = visionPath,
                    prompt = user.second,
                    predictLength = predictLength ?: InferenceEngine.DEFAULT_PREDICT_LENGTH
                ).collect { token -> builder.append(token) }
                builder.toString()
            } else ""
            if (visionResult.isNotBlank()) {
                emit(visionResult)
            } else {
                engine.sendUserPrompt(
                    message = user.second,
                    predictLength = predictLength ?: InferenceEngine.DEFAULT_PREDICT_LENGTH
                )
                    .catch { e -> throw IllegalStateException("Ошибка генерации: ${e.message}", e) }
                    .collect { token -> emit(token) }
            }
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
        const val DEFAULT_PERSONA = "Ты AIIA — дружелюбный ИИ-напарник."
        const val DEFAULT_N_BATCH = 512

        fun autoBatchFor(ramBytes: Long): Int =
            ((ramBytes / (1024 * 1024 * 1024)) * 256)
                .toInt()
                .coerceIn(DEFAULT_N_BATCH, 2048)
    }
}

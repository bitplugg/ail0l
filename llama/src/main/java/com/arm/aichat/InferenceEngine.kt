package com.arm.aichat

import kotlinx.coroutines.flow.StateFlow






interface InferenceEngine {
    val state: StateFlow<State>

    suspend fun loadModel(pathToModel: String)


    suspend fun ensureInitialized()


    suspend fun configure(nCtx: Int, nThreads: Int, flashAttn: Boolean, nBatch: Int)


    suspend fun setSamplerParams(temp: Float, topK: Int, topP: Float)


    suspend fun setLoraAdapter(path: String, scale: Float = 1.0f) {}


    suspend fun setMmproj(path: String) {}


    suspend fun analyzeImage(path: String, prompt: String = "Опиши изображение и распознай текст"): String = ""


    suspend fun saveContextCache(path: String): Boolean = false
    suspend fun loadContextCache(path: String): Boolean = false


    suspend fun hydrateContext(systemPrompt: String, roles: List<String>, contents: List<String>)

    suspend fun setSystemPrompt(systemPrompt: String)

    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): kotlinx.coroutines.flow.Flow<String>

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    fun cleanUp()

    fun destroy()

    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val InferenceEngine.State.isUninterruptible
    get() = this is InferenceEngine.State.Initializing ||
        this is InferenceEngine.State.LoadingModel ||
        this is InferenceEngine.State.UnloadingModel ||
        this is InferenceEngine.State.Benchmarking ||
        this is InferenceEngine.State.ProcessingSystemPrompt ||
        this is InferenceEngine.State.ProcessingUserPrompt

val InferenceEngine.State.isModelLoaded: Boolean
    get() = this is InferenceEngine.State.ModelReady ||
        this is InferenceEngine.State.Benchmarking ||
        this is InferenceEngine.State.ProcessingSystemPrompt ||
        this is InferenceEngine.State.ProcessingUserPrompt ||
        this is InferenceEngine.State.Generating

class UnsupportedArchitectureException : Exception()

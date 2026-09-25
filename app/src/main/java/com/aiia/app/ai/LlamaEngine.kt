package com.aiia.app.ai

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine

class LlamaEngine private constructor(
    private val context: Context,
    private val delegate: InferenceEngine
) {
    val state get() = delegate.state

    suspend fun loadModel(path: String) = delegate.loadModel(path)
    suspend fun ensureInitialized() = delegate.ensureInitialized()
    suspend fun configure(nCtx: Int, nThreads: Int, flashAttn: Boolean, nBatch: Int) =
        delegate.configure(nCtx, nThreads, flashAttn, nBatch)
    suspend fun setSamplerParams(temp: Float, topK: Int, topP: Float) =
        delegate.setSamplerParams(temp, topK, topP)
    suspend fun setLoraAdapter(path: String, scale: Float) = delegate.setLoraAdapter(path, scale)
    suspend fun setMmproj(path: String) = delegate.setMmproj(path)
    suspend fun analyzeImage(path: String, prompt: String): String =
        delegate.analyzeImage(path, prompt)
    suspend fun analyzeImage(bytes: ByteArray, prompt: String): String {
        val file = java.io.File.createTempFile("aiia-image-", ".jpg", context.cacheDir)
        return try {
            file.writeBytes(bytes)
            delegate.analyzeImage(file.absolutePath, prompt)
        } finally {
            file.delete()
        }
    }
    suspend fun saveContextCache(path: String): Boolean = delegate.saveContextCache(path)
    suspend fun loadContextCache(path: String): Boolean = delegate.loadContextCache(path)
    fun sendUserPrompt(message: String, predictLength: Int = InferenceEngine.DEFAULT_PREDICT_LENGTH) =
        delegate.sendUserPrompt(message, predictLength)
    fun cleanUp() = delegate.cleanUp()

    companion object {
        fun get(context: Context): LlamaEngine =
            LlamaEngine(context.applicationContext, AiChat.getInferenceEngine(context.applicationContext))
    }
}

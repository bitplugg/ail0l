package com.arm.aichat

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl

/**
 * Main entry point for the bundled llama.cpp binding.
 * Vendored from ggml-org/llama.cpp `examples/llama.android` (MIT License).
 */
object AiChat {
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}
package com.arm.aichat

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl





object AiChat {
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}

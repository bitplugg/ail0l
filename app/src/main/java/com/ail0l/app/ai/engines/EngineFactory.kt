package com.ail0l.app.ai.engines

import android.content.Context
import com.ail0l.app.ai.Engine
import com.ail0l.app.data.AppDatabase
import com.ail0l.app.data.Settings
import com.arm.aichat.AiChat

/**
 * Создаёт подходящий [AiEngine] на основе текущих настроек.
 */
class EngineFactory(private val context: Context) {

    fun engineFor(settings: Settings): AiEngine = when (settings.engine) {
        Engine.LOCAL -> LocalLlamaEngine(
            engine = AiChat.getInferenceEngine(context),
            settings = settings
        )

        Engine.MISTRAL -> OpenAiEngine(
            baseUrl = "https://api.mistral.ai/v1",
            apiKey = settings.mistralApiKey,
            model = settings.mistralModel,
            label = "Mistral ${settings.mistralModel}"
        )

        Engine.OPENAI -> OpenAiEngine(
            baseUrl = settings.openAiBaseUrl,
            apiKey = settings.openAiApiKey,
            model = settings.openAiModel,
            label = "OpenAI ${settings.openAiModel}"
        )

        Engine.ANTHROPIC -> AnthropicEngine(
            apiKey = settings.anthropicApiKey,
            model = settings.anthropicModel,
            label = "Claude ${settings.anthropicModel}"
        )
    }
}
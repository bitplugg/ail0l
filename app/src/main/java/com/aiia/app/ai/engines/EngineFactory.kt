package com.aiia.app.ai.engines

import android.content.Context
import com.aiia.app.ai.Engine
import com.aiia.app.ai.download.DeviceProfile
import com.aiia.app.agent.ContextCacheManager
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.Settings
import com.arm.aichat.AiChat

class EngineFactory(
    private val context: Context,
    private val contextCache: ContextCacheManager? = null
) {

    fun engineFor(settings: Settings): AiEngine = when (settings.engine) {
        Engine.LOCAL -> LocalLlamaEngine(
            engine = AiChat.getInferenceEngine(context),
            settings = settings,
            nBatch = LocalLlamaEngine.autoBatchFor(DeviceProfile.usableRamBytes(context)),
            contextCache = contextCache
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

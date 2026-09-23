package com.ail0l.app.ai.engines

import com.ail0l.app.ai.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * Универсальный интерфейс ИИ-движка.
 * [chat] возвращает поток текстовых дельт (токенов) финального ответа ассистента.
 */
interface AiEngine {
    val label: String
    fun chat(messages: List<ChatMessage>): Flow<String>
    suspend fun release() {}
}
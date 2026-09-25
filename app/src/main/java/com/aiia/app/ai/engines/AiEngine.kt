package com.aiia.app.ai.engines

import com.aiia.app.ai.ChatMessage
import kotlinx.coroutines.flow.Flow

interface AiEngine {
    val label: String
    fun chat(messages: List<ChatMessage>, predictLength: Int? = null): Flow<String>
    suspend fun release() {}
}

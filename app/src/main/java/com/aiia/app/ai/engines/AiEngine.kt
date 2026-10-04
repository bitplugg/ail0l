package com.aiia.app.ai.engines

import com.aiia.app.ai.ChatMessage
import kotlinx.coroutines.flow.Flow

interface AiEngine {
    val label: String

    fun chat(messages: List<ChatMessage>, predictLength: Int? = null): Flow<String>

    /**
     * Constrains the next generation with GBNF; null clears it. Engines that sample remotely have
     * no such control, so the default is a no-op rather than pretending the constraint applies.
     */
    suspend fun setGrammar(grammar: String?) {}

    suspend fun release() {}
}

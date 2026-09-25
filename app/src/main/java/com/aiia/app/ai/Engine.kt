package com.aiia.app.ai

enum class Engine { LOCAL, MISTRAL, OPENAI, ANTHROPIC }

sealed interface ChatMessage {
    val role: String
    val content: String

    data class System(override val content: String) : ChatMessage {
        override val role = "system"
    }

    data class User(
        override val content: String,
        val images: List<ImageAttachment> = emptyList()
    ) : ChatMessage {
        override val role = "user"
    }

    data class Assistant(override val content: String) : ChatMessage {
        override val role = "assistant"
    }
}

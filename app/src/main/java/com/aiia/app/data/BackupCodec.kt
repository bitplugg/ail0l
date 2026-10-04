package com.aiia.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned export format for conversations and memory.
 *
 * Plain text transcripts exported by older builds are still importable through
 * [LegacyTranscript], so switching formats does not lock anyone out of their history.
 */
@Serializable
data class BackupDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val conversations: List<BackupConversation> = emptyList(),
    val facts: List<BackupFact> = emptyList()
) {
    companion object {
        const val FORMAT = "aiia-backup"
        const val VERSION = 1
    }

    val messageCount: Int get() = conversations.sumOf { it.messages.size }
}

@Serializable
data class BackupConversation(
    val title: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = 0L,
    val messages: List<BackupMessage> = emptyList()
)

@Serializable
data class BackupMessage(
    val role: String,
    val content: String,
    val createdAt: Long = 0L,
    /** Chain-of-thought, kept so a restored conversation looks like the original. */
    val reasoning: String = ""
)

@Serializable
data class BackupFact(
    val fact: String,
    val category: String = "user",
    val favorite: Boolean = false,
    val createdAt: Long = 0L
)

object BackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(document: BackupDocument): String = json.encodeToString(BackupDocument.serializer(), document)

    /** Returns null for anything that is not an AIIA backup, so callers can try other formats. */
    fun decode(text: String): BackupDocument? = runCatching {
        val parsed = json.decodeFromString(BackupDocument.serializer(), text)
        if (parsed.format != BackupDocument.FORMAT) null else parsed.copy(version = BackupDocument.VERSION)
    }.getOrNull()

    fun looksLikeBackup(text: String): Boolean = decode(text) != null

    fun isEmpty(document: BackupDocument): Boolean = document.conversations.isEmpty() && document.facts.isEmpty()
}

/**
 * Reads the transcript format produced by the "Поделиться" export of older builds:
 * `# title`, then `[Вы]:` / `[AIIA]:` blocks.
 */
object LegacyTranscript {

    private val SPEAKER = Regex("""^\[([^\]]{1,32})]\s*:?\s*(.*)$""")
    private const val MAX_MESSAGES = 500

    fun parse(text: String): List<BackupMessage> {
        if (text.isBlank()) return emptyList()
        val parsed = mutableListOf<BackupMessage>()
        var role: String? = null
        val body = StringBuilder()

        fun flush() {
            val current = role ?: return
            val content = body.toString().trim()
            if (content.isNotEmpty()) parsed += BackupMessage(role = current, content = content)
            body.setLength(0)
        }

        val lines = text.replace("\r", "").split('\n').map { it.trim() }.filterNot { it.startsWith("#") }
        for (line in lines) {
            val match = SPEAKER.find(line)
            if (match != null) {
                flush()
                role = normalizeRole(match.groupValues[1])
                body.append(match.groupValues[2].trim())
            } else if (role != null) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
            }
            if (parsed.size >= MAX_MESSAGES) break
        }
        flush()
        return parsed
    }

    fun title(text: String): String = text.lineSequence()
        .firstOrNull { it.trim().startsWith("#") }
        ?.trimStart('#', ' ')
        ?.takeIf { it.isNotBlank() }
        ?: "Импортированный диалог"

    private fun normalizeRole(speaker: String): String = when {
        speaker.contains("user", ignoreCase = true) -> "user"
        speaker.contains("assistant", ignoreCase = true) -> "assistant"
        speaker.contains("system", ignoreCase = true) -> "system"
        speaker.contains("вы", ignoreCase = true) -> "user"
        speaker.contains("a", ignoreCase = true) && speaker.contains("i", ignoreCase = true) -> "assistant"
        else -> "assistant"
    }
}

package com.aiia.app.memory

import com.aiia.app.data.AppDatabase
import com.aiia.app.data.entities.FactEntity
import com.aiia.app.data.entities.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.util.regex.Pattern

class MemoryManager(private val db: AppDatabase) {

    fun isMemoryCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith(PREFIX_REMEMBER) ||
            t.startsWith(PREFIX_FORGET) ||
            t.startsWith(PREFIX_FIND) ||
            t.contains(QUERY_KNOW) ||
            isDayQuestion(t) ||
            isSearchQuestion(t)
    }

    sealed interface MemoryResult {
        data object Nothing : MemoryResult
        data class FactAdded(val fact: String) : MemoryResult
        data class FactRemoved(val fact: String) : MemoryResult
        data object FactMissing : MemoryResult
        data class FactList(val facts: List<FactEntity>) : MemoryResult
        data class DayFacts(val label: String, val facts: List<FactEntity>) : MemoryResult
        data class SearchHits(val hits: List<Pair<String, String>>) : MemoryResult
    }

    suspend fun handleCommand(text: String): MemoryResult {
        val t = text.trim()
        return when {
            t.lowercase().startsWith(PREFIX_REMEMBER) -> {
                val fact = t.substring(PREFIX_REMEMBER.length).trim().trimEnd('!', '.', ',')
                if (fact.isBlank()) MemoryResult.Nothing
                else {
                    db.dao().insertFact(FactEntity(fact = fact))
                    MemoryResult.FactAdded(fact)
                }
            }

            t.lowercase().startsWith(PREFIX_FORGET) -> {
                val needle = t.substring(PREFIX_FORGET.length).trim().trimEnd('.', ',')
                if (needle.isBlank()) return MemoryResult.Nothing
                val found = db.dao().observeFacts().first()
                    .firstOrNull { it.fact.contains(needle, ignoreCase = true) }
                if (found == null) MemoryResult.FactMissing
                else {
                    db.dao().deleteFact(found)
                    db.dao().deleteEmbedding(found.id)
                    MemoryResult.FactRemoved(found.fact)
                }
            }

            t.lowercase().contains(QUERY_KNOW) ->
                MemoryResult.FactList(db.dao().topFacts(100))

            t.lowercase().startsWith(PREFIX_FIND) -> {
                val q = t.substring(PREFIX_FIND.length).trim().trimEnd('.', ',')
                if (q.isBlank()) MemoryResult.FactList(emptyList())
                else MemoryResult.FactList(db.dao().findFacts(q))
            }

            isDayQuestion(t) -> dayFacts(t)

            isSearchQuestion(t) -> searchDialogs(text)

            else -> MemoryResult.Nothing
        }
    }

    private suspend fun dayFacts(t: String): MemoryResult {
        val label = when {
            t.contains("позавчера") -> "позавчера"
            t.contains("сегодня") -> "сегодня"
            else -> "вчера"
        }
        val now = System.currentTimeMillis()
        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val (from, to) = when (label) {
            "позавчера" -> (dayStart.timeInMillis - 86_400_000L) to dayStart.timeInMillis
            "сегодня" -> dayStart.timeInMillis to (dayStart.timeInMillis + 86_400_000L - 1L)
            else -> (dayStart.timeInMillis - 86_400_000L) to dayStart.timeInMillis
        }
        return MemoryResult.DayFacts(label, db.dao().factsBetween(from, to))
    }

    private suspend fun searchDialogs(text: String): MemoryResult {
        val t = text.trim().lowercase()
        val matcher = SEARCH_PATTERN.matcher(t)
        if (!matcher.find()) return MemoryResult.Nothing
        val q = matcher.group(1)?.trim()?.trimEnd('?', '!', '.', ' ') ?: return MemoryResult.Nothing
        if (q.isBlank()) return MemoryResult.Nothing
        val hits = db.dao().searchMessages(q, 30)
        if (hits.isEmpty()) return MemoryResult.SearchHits(emptyList())

        val convs = db.dao().observeConversations().first()
            .associate { it.id to it.title }
        val grouped = hits.mapNotNull { m ->
            if (m.role == "system") null
            else (convs[m.conversationId] ?: "Беседа") to snippet(m)
        }
        return MemoryResult.SearchHits(grouped.distinctBy { it.first + "|" + it.second }.take(12))
    }

    private fun snippet(m: MessageEntity): String {
        val role = if (m.role == "assistant") "А" else "Я"
        val text = m.content.replace(Regex("\\s+"), " ").trim().take(120)
        return "$role: $text"
    }

    private fun isDayQuestion(t: String): Boolean {
        val hasFlag = t.contains("сегодня") || t.contains("вчера") || t.contains("позавчера")
        val asksActivity = t.contains("что я делал") || t.contains("что мы делали") ||
            t.contains("что было") || t.contains("чем я занима")
        return hasFlag && (asksActivity || t.contains("записано") || t.contains("что запомнил"))
    }

    private fun isSearchQuestion(t: String): Boolean = SEARCH_PATTERN.matcher(t).find()

    suspend fun autoLearn(text: String) {
        val lower = text.lowercase()

        val name = NAME_PATTERN.matcher(lower).takeIf { it.find() }?.group(2)
        if (name != null && name.isNotBlank()) {
            db.dao().insertFact(FactEntity(fact = "Пользователя зовут $name"))
        }

        val age = AGE_PATTERN.matcher(lower).takeIf { it.find() }?.group(1)
        if (age != null) {
            db.dao().insertFact(FactEntity(fact = "Пользователю $age лет"))
        }

        for ((key, template) in SIMPLE_RULES) {
            if (!lower.contains(key)) continue
            val value = text.substring(text.lowercase().indexOf(key) + key.length).trim()
                .substringBefore('.').substringBefore('!').substringBefore('?')
                .trim().trimEnd(',', ';')
            if (value.isBlank()) continue
            db.dao().insertFact(FactEntity(fact = "$template $value".trim()))
        }
    }

    companion object {
        private const val PREFIX_REMEMBER = "запомни:"
        private const val PREFIX_FORGET = "забудь:"
        private const val PREFIX_FIND = "найди:"
        private const val QUERY_KNOW = "что ты обо мне знаешь"

        private val SEARCH_PATTERN = Pattern.compile(
            "что (?:мы )?(?:обсуждали|говорили|писали|чати[а-я]*)\\s+(?:про|о|об|о том)\\s*(.+)",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )

        private val NAME_PATTERN =
            Pattern.compile("(меня зовут|моё имя|мое имя)\\s+([а-яё]+)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        private val AGE_PATTERN =
            Pattern.compile("мне\\s+(\\d{1,3})\\s*(лет|год|года)", Pattern.CASE_INSENSITIVE)

        private val SIMPLE_RULES = listOf(
            "я живу в" to "Пользователь живёт в",
            "я из " to "Пользователь родом из",
            "я работаю" to "Пользователь работает:",
            "я учусь" to "Пользователь учится:"
        )
    }
}

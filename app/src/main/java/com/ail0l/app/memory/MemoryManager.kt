package com.ail0l.app.memory

import com.ail0l.app.data.AppDatabase
import com.ail0l.app.data.entities.FactEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.util.regex.Pattern

/**
 * Управление долговременной памятью агента.
 *
 * — команды в чате: «запомни: …» (новый факт), «забудь: …» (удалить),
 *   «что ты обо мне знаешь?» (перечислить факты);
 * — автообучение эвристиками из реплик пользователя.
 */
class MemoryManager(private val db: AppDatabase) {

    fun isMemoryCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith(PREFIX_REMEMBER) ||
            t.startsWith(PREFIX_FORGET) ||
            t.startsWith(PREFIX_FIND) ||
            t.contains(QUERY_KNOW)
    }

    sealed interface MemoryResult {
        data object Nothing : MemoryResult
        data class FactAdded(val fact: String) : MemoryResult
        data class FactRemoved(val fact: String) : MemoryResult
        data object FactMissing : MemoryResult
        data class FactList(val facts: List<FactEntity>) : MemoryResult
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

            else -> MemoryResult.Nothing
        }
    }

    /** Эвристическое «обучение» из реплики пользователя (куда вставится в промпт — решает список фактов) */
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
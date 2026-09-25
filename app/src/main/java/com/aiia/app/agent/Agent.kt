package com.aiia.app.agent

import android.content.Context
import com.aiia.app.ai.ChatMessage
import com.aiia.app.ai.ImageAttachment
import com.aiia.app.ai.download.DeviceProfile
import com.aiia.app.ai.engines.AiEngine
import com.aiia.app.ai.engines.EngineFactory
import com.aiia.app.ai.search.SearchResult
import com.aiia.app.ai.search.WebSearch
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.Settings
import com.aiia.app.data.SettingsRepository
import com.aiia.app.data.entities.ConversationEntity
import com.aiia.app.data.entities.MessageEntity
import com.aiia.app.data.entities.OutboxEntity
import com.aiia.app.memory.MemoryManager
import com.aiia.app.persona.PersonaRepository
import com.aiia.app.plugins.mcp.McpManager
import com.aiia.app.agent.tools.ToolCall
import com.aiia.app.agent.tools.ToolCallParser
import com.aiia.app.agent.tools.ToolConfirmationCoordinator
import com.aiia.app.util.Reminders
import com.aiia.app.util.SystemCommands
import com.aiia.app.util.ThoughtLog
import com.aiia.app.util.UsageLog
import com.aiia.app.util.Weather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Agent(
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val engineFactory: EngineFactory,
    private val memory: MemoryManager,
    private val appContext: Context,
    private val personaRepository: PersonaRepository? = null,
    private val toolConfirmation: ToolConfirmationCoordinator? = null,
    private val vectorSearch: VectorSearchEngine? = null,
    private val mcp: McpManager? = null,
    private val webSearch: WebSearch = WebSearch()
) {

    sealed interface Event {
        data class Token(val text: String) : Event
        data class Done(val full: String, val engineLabel: String) : Event
        data class Failure(val message: String) : Event
        data class ToolRequired(val call: ToolCall) : Event
    }

    private val busy = HashSet<Long>()

    fun send(
        conversationId: Long,
        text: String,
        predictLength: Int? = null,
        images: List<ImageAttachment> = emptyList()
    ): Flow<Event> = flow {
        val trimmed = text.trim()
        check(trimmed.isNotEmpty()) { "Пустое сообщение" }
        require(busy.add(conversationId)) { "Уже генерируем ответ в этом диалоге" }
        try {
            val settings = settingsRepo.settings.first()
            val conversation = db.dao().observeConversation(conversationId).first()

            val recent = if (conversation != null)
                db.dao().recentMessages(conversationId, RECENT_LIMIT) else emptyList()

            db.dao().insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = trimmed,
                    attachments = images.joinToString("\n") { it.uri }
                )
            )
            if (conversation != null) {
                if (conversation.title == "Новый диалог" || conversation.title.isBlank()) {
                    db.dao().renameConversation(conversationId, trimmed.take(40))
                }
                db.dao().saveSummary(conversationId, conversation.summary)
            }

            CALL_PATTERN.matchEntire(trimmed)?.let { m ->
                val name = m.groupValues[1].trim()
                val body = m.groupValues[2].trim()
                if (body.isEmpty()) {
                    emit(Event.Done("Пустое сообщение устройству.", "direct"))
                    return@flow
                }
                val contacts = runCatching {
                    Json.parseToJsonElement(settings.contactsJson.ifBlank { "{}" }).jsonObject
                }.getOrNull() ?: kotlinx.serialization.json.buildJsonObject {}
                val deviceId = contacts.entries.firstOrNull { it.key.equals(name, true) }?.value?.jsonPrimitive?.content
                if (deviceId.isNullOrBlank()) {
                    emit(Event.Done("Нет устройства «$name» в списке.", "direct"))
                    return@flow
                }
                db.dao().enqueueOutbox(
                    OutboxEntity(conversationId = conversationId, content = body, toDevice = deviceId)
                )
                ThoughtLog.add(ThoughtLog.Tag.TOOL, "Сообщение отправлено на устройство «$name»")
                emit(Event.Done("Отправлено на «$name».", "direct"))
                return@flow
            }

            SystemCommands.handle(appContext, trimmed)?.let { reply ->
                enqueueOutboxIfSync(settings, conversationId, trimmed)
                emit(Event.Done(reply, "system"))
                return@flow
            }

            if (isCancelReminders(trimmed)) {
                val upcoming = db.dao().upcomingReminders()
                if (upcoming.isEmpty()) {
                    emit(Event.Done("Активных напоминаний нет.", "reminder"))
                    return@flow
                }
                for (r in upcoming) {
                    Reminders.cancel(appContext, r.id, r.isTimer)
                    db.dao().deleteReminder(r.id)
                }
                ThoughtLog.add(ThoughtLog.Tag.TOOL, "Отменены напоминания: ${upcoming.size} шт.")
                emit(Event.Done("Отменил все активные напоминания (${upcoming.size}).", "reminder"))
                return@flow
            }
            Reminders.parse(trimmed)?.let { req ->
                val id = Reminders.schedule(appContext, req)
                ThoughtLog.add(ThoughtLog.Tag.TOOL, if (req.isTimer) "Таймер запланирован" else "Напоминание запланировано")
                emit(Event.Done(
                    if (req.isTimer) "Поставил таймер: ⏱ ${req.text}"
                    else "Напомню: ⏰ ${req.text}",
                    "reminder"
                ))
                return@flow
            }

            var weatherInfo: String? = null
            if (Weather.isAsking(trimmed)) {
                val city = Weather.cityText(trimmed)
                if (city.isNullOrBlank()) {
                    emit(Event.Done(
                        "Скажи город, например «погода в Москве» или «погода в СПб».",
                        "weather"
                    ))
                    return@flow
                }
                val summary = Weather.byCityText(trimmed)
                if (summary == null) {
                    emit(Event.Done(
                        "Не нашёл погоду для «$city». Попробуй город в именительном падеже, например «погода в Москва».",
                        "weather"
                    ))
                    return@flow
                }
                weatherInfo = summary
                ThoughtLog.add(ThoughtLog.Tag.TOOL, "Получена погода: $summary")
            }

            var openAiEnabled = false
            if (settings.memoryEnabled) {
                when (val r = memory.handleCommand(trimmed)) {
                    is MemoryManager.MemoryResult.FactAdded -> {
                        enqueueOutboxIfSync(settings, conversationId, trimmed)
                        ThoughtLog.add(ThoughtLog.Tag.MEMORY, "Запомнил: ${r.fact}")
                        emit(Event.Done("Запомнил: ${r.fact}", "memory"))
                        return@flow
                    }

                    is MemoryManager.MemoryResult.FactRemoved -> {
                        enqueueOutboxIfSync(settings, conversationId, trimmed)
                        ThoughtLog.add(ThoughtLog.Tag.MEMORY, "Забыл: ${r.fact}")
                        emit(Event.Done("Забыл: ${r.fact}", "memory"))
                        return@flow
                    }

                    is MemoryManager.MemoryResult.FactMissing -> {
                        enqueueOutboxIfSync(settings, conversationId, trimmed)
                        ThoughtLog.add(ThoughtLog.Tag.MEMORY, "Искал факт по «${trimmed}» — не нашёл")
                        emit(Event.Done("Такого факта у меня нет.", "memory"))
                        return@flow
                    }

                    is MemoryManager.MemoryResult.FactList -> {
                        enqueueOutboxIfSync(settings, conversationId, trimmed)
                        ThoughtLog.add(ThoughtLog.Tag.MEMORY, "Запрошены факты памяти: ${r.facts.size} шт.")
                        val list = r.facts.joinToString("\n") { "• ${it.fact}" }.ifBlank { "Пока пусто." }
                        emit(Event.Done("Моя память о тебе:\n$list", "memory"))
                        return@flow
                    }

                    MemoryManager.MemoryResult.Nothing -> Unit

                    is MemoryManager.MemoryResult.DayFacts -> {
                        enqueueOutboxIfSync(settings, conversationId, trimmed)
                        ThoughtLog.add(ThoughtLog.Tag.MEMORY, "Факты за ${r.label}: ${r.facts.size} шт.")
                        val label = when (r.label) {
                            "вчера" -> "Вчерашний день"
                            "позавчера" -> "Позавчерашний день"
                            else -> "Сегодняшний день"
                        }
                        if (r.facts.isEmpty()) {
                            emit(Event.Done("$label — я ничего не записал в память.", "memory"))
                        } else {
                            val list = r.facts.joinToString("\n") { "• ${it.fact}" }
                            emit(Event.Done("$label:\n$list", "memory"))
                        }
                        return@flow
                    }

                    is MemoryManager.MemoryResult.SearchHits -> {
                        enqueueOutboxIfSync(settings, conversationId, trimmed)
                        ThoughtLog.add(ThoughtLog.Tag.MEMORY, "Поиск по диалогам: ${r.hits.size} совпадений")
                        if (r.hits.isEmpty()) {
                            emit(Event.Done("Ничего не нашлось в диалогах по этому запросу.", "memory"))
                        } else {
                            val text = r.hits.joinToString("\n") { (conv, snip) ->
                                "— $conv\n   $snip"
                            }
                            emit(Event.Done("Вот что я нашёл в диалогах:\n$text", "memory"))
                        }
                        return@flow
                    }
                }
                if (settings.autoLearnEnabled) memory.autoLearn(trimmed)
            }

            enqueueOutboxIfSync(settings, conversationId, trimmed)

            val webResults = if (trimmed.lowercase().startsWith(PREFIX_WEB)) {
                val q = trimmed.substring(PREFIX_WEB.length).trim()
                if (q.isNotBlank()) {
                    val res = webSearch.search(q, settings.searchUrl, settings.searchKey)
                    ThoughtLog.add(ThoughtLog.Tag.TOOL, "Веб-поиск «$q»: ${res.size} результатов")
                    res
                } else emptyList()
            } else emptyList()
            val contextMessages = buildContext(conversationId, trimmed, recent, settings, webResults, weatherInfo, images)

            val engine = engineFactory.engineFor(settings)
            val sb = StringBuilder()
            val genStartMs = if (predictLength != null) System.currentTimeMillis() else 0L
            var tokens = 0
            var quick = false
            try {
                if (predictLength != null) {
                    quick = true
                    ThoughtLog.add(
                        ThoughtLog.Tag.THINK,
                        "Быстрый ответ включён: лимит ${predictLength} токенов (${engine.label})"
                    )
                }
                emit(Event.Token(""))
                ThoughtLog.add(ThoughtLog.Tag.THINK, "Размышляю над ответом… (${engine.label})")
                GenerationCoordinator.withGeneration {
                    engine.chat(contextMessages, predictLength).collect { tok ->
                        sb.append(tok)
                        tokens++
                        emit(Event.Token(tok))
                    }
                }
            } catch (e: CancellationException) {
                if (sb.isNotBlank()) persistAssistant(conversationId, sb.toString(), "cancelled")
                throw e
            } catch (e: Exception) {
                if (sb.isNotBlank()) persistAssistant(conversationId, sb.toString(), "partial")
                emit(Event.Failure("движок: ${e.message}"))
                return@flow
            }

            val full = sb.toString().trim()
            if (full.isNotBlank()) {
                ToolCallParser().parse(full)?.let { call ->
                    toolConfirmation?.request(call)
                    emit(Event.ToolRequired(call))
                }
                persistAssistant(conversationId, full, "done")
                enqueueOutboxIfSync(settings, conversationId, full)
                summarizeIfLong(conversationId, engine, settings)
            } else {
                db.dao().insertMessage(
                    MessageEntity(conversationId = conversationId, role = "assistant", content = "(пустой ответ)")
                )
            }

            emit(Event.Done(full, engine.label))
            ThoughtLog.add(
                ThoughtLog.Tag.THINK,
                generationReport(full, tokens, genStartMs, quick) + " · движок ${engine.label}"
            )
            UsageLog.record(
                appContext,
                tokens,
                if (genStartMs != 0L) System.currentTimeMillis() - genStartMs else 0L
            )
        } finally {
            busy.remove(conversationId)
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun persistAssistant(conversationId: Long, text: String, status: String) {
        db.dao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = text, status = status)
        )
    }

    private fun generationReport(full: String, tokens: Int, startMs: Long, quick: Boolean): String {
        if (startMs == 0L) return "Ответ готов: ${full.length} симв."
        val elapsedMs = System.currentTimeMillis() - startMs
        val tps = if (elapsedMs > 0L) tokens * 1000f / elapsedMs else 0f
        return buildString {
            append("Ответ готов: ${full.length} симв. · %d ток. · %.1f ток/с · %.1f с".format(
                tokens, tps, elapsedMs / 1000f
            ))
            if (quick) append(" · (быстро)")
        }
    }

    private suspend fun enqueueOutboxIfSync(settings: Settings, conversationId: Long, content: String) {
        if (settings.syncEnabled) {
            db.dao().enqueueOutbox(OutboxEntity(conversationId = conversationId, content = content))
            ThoughtLog.add(ThoughtLog.Tag.SYNC, "В outbox добавлено сообщение (${content.length} симв.)")
        }
    }

    private fun deviceFingerprint(settings: Settings): String =
        "${settings.syncDeviceId.ifBlank { "device" }} | ${DeviceProfile.abi()} | ${DeviceProfile.cores()} cores"

    private suspend fun buildContext(
        conversationId: Long,
        userText: String,
        recent: List<MessageEntity>,
        settings: Settings,
        webResults: List<SearchResult> = emptyList(),
        weatherInfo: String? = null,
        images: List<ImageAttachment> = emptyList()
    ): List<ChatMessage> {
        val selectedPersona = personaRepository?.selected()
        val system = buildString {
            append(selectedPersona?.systemPrompt?.trim().orEmpty().ifBlank { settings.persona.trim() })
            append("\n\nУстройство: ${deviceFingerprint(settings)}")
            weatherInfo?.let { append("\n\nДанные погоды (актуальны): $it") }
            if (settings.memoryEnabled) {
                if (settings.ragEnabled && vectorSearch != null) {
                    val relevant = vectorSearch.context(userText, 5)
                    if (relevant.isNotBlank()) append("\n\nСемантически релевантные факты:\n$relevant")
                }
                val facts = db.dao().topFacts(50)
                if (facts.isNotEmpty()) {
                    append("\n\nДолговременная память о пользователе:\n")
                    append(facts.joinToString("\n") { "- ${it.fact}" })
                }
                val conv = db.dao().observeConversation(conversationId).first()
                if (conv?.summary.isNullOrBlank().not()) {
                    append("\n\nИтоги прошлых бесед:\n").append(conv?.summary)
                }
            }
            mcp?.tools?.value?.takeIf { it.isNotEmpty() }?.let { tools ->
                append("\n\nДоступные MCP-инструменты (вызов только через подтверждение пользователя):\n")
                append(tools.joinToString("\n") { "- ${it.prompt()}" })
            }
            if (webResults.isNotEmpty()) {
                append("\n\nРезультаты веб-поиска. Ответь на запрос пользователя на их основе, " +
                    "приведи ссылки на источники:\n")
                append(webResults.joinToString("\n") { r ->
                    "- ${r.title}: ${r.snippet}${if (r.url.isNotBlank()) " (${r.url})" else ""}"
                })
            }
        }

        ThoughtLog.context(system)

        return buildList {
            add(ChatMessage.System(system))
            recent.forEach { m ->
                when (m.role) {
                    "assistant" -> add(ChatMessage.Assistant(m.content))
                    else -> add(ChatMessage.User(m.content))
                }
            }
            add(ChatMessage.User(userText, images))
        }
    }

    private suspend fun summarizeIfLong(conversationId: Long, engine: AiEngine, settings: Settings) {
        val lastSummaryAt = lastSummarizedAt[conversationId] ?: 0L
        val count = db.dao().recentMessages(conversationId, 100).size
        if (count - lastSummaryAt < SUMMARY_EVERY_MESSAGES) return
        if (count < SUMMARY_MIN_MESSAGES) return

        try {
            val tail = db.dao().recentMessages(conversationId, 20).reversed()
            val prompt = buildList {
                add(ChatMessage.System(SUMMARY_PROMPT))
                tail.forEach {
                    add(if (it.role == "assistant") ChatMessage.Assistant(it.content) else ChatMessage.User(it.content))
                }
            }
            val sb = StringBuilder()
            engine.chat(prompt).collect { sb.append(it) }
            val summary = sb.toString().trim().take(1200)
            if (summary.isNotBlank()) {
                db.dao().saveSummary(conversationId, summary)
                ThoughtLog.add(ThoughtLog.Tag.THINK, "Автосуммаризация: сохранены итоги беседы")
            }
            lastSummarizedAt[conversationId] = count.toLong()
        } catch (e: Exception) {

        }
    }

    private val lastSummarizedAt = HashMap<Long, Long>()

    companion object {
        private const val RECENT_LIMIT = 20
        private const val SUMMARY_MIN_MESSAGES = 24
        private const val SUMMARY_EVERY_MESSAGES = 20
        private const val PREFIX_WEB = "найди в интернете:"
        private val CALL_PATTERN =
            Regex("""позови\s+(\S+)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)

        private fun isCancelReminders(text: String): Boolean {
            val t = text.trim().lowercase()
            return (t.startsWith("отмени ") || t.startsWith("удали ") || t.startsWith("сними ")) &&
                (t.contains("напоминани") || t.contains("таймеры") || t.contains("таймер "))
        }
        private const val SUMMARY_PROMPT =
            "Сожми беседу в 3-5 предложений долговременных фактов " +
                "о пользователе и текущей задаче. Только факты, без приветствий."
    }
}

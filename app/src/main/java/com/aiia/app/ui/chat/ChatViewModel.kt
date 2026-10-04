package com.aiia.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiia.app.agent.Agent
import com.aiia.app.agent.tools.ToolCall
import com.aiia.app.ai.ImageAttachment
import com.aiia.app.data.BackupCodec
import com.aiia.app.data.BackupConversation
import com.aiia.app.data.BackupDocument
import com.aiia.app.data.BackupFact
import com.aiia.app.data.BackupMessage
import com.aiia.app.data.LegacyTranscript
import com.aiia.app.data.entities.ConversationEntity
import com.aiia.app.data.entities.FactEntity
import com.aiia.app.data.entities.MessageEntity
import com.aiia.app.data.entities.OutboxEntity
import com.aiia.app.dm.Dependencies
import com.aiia.app.util.ThoughtLog
import com.aiia.app.util.Tts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = Dependencies.db.dao()
    private val agent: Agent = Dependencies.agent
    private val personaRepository = Dependencies.personas

    private val _convId = MutableStateFlow(0L)
    private val _input = MutableStateFlow("")
    private val _sending = MutableStateFlow(false)
    private val _streaming = MutableStateFlow("")

    private val _streamingThinking = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _notice = MutableStateFlow<String?>(null)
    private val _quickMode = MutableStateFlow(false)
    private val _thoughts = MutableStateFlow(ThoughtLog.entries())
    private val _syncStatus = MutableStateFlow<String?>(null)
    private val _attachments = MutableStateFlow<List<ImageAttachment>>(emptyList())
    private val _pendingToolCall = MutableStateFlow<ToolCall?>(null)
    private val _counts = MutableStateFlow(Triple(0, 0, 0))

    val conversations: StateFlow<List<ConversationEntity>> =
        dao.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentConversation: StateFlow<ConversationEntity?> =
        combine(_convId, conversations) { id, list ->
            list.firstOrNull { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<MessageEntity>> =
        _convId.flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList()) else dao.observeMessages(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val input: StateFlow<String> = _input.asStateFlow()
    val sending: StateFlow<Boolean> = _sending.asStateFlow()
    val streaming: StateFlow<String> = _streaming.asStateFlow()

    /** Live chain-of-thought, rendered separately from the answer. */
    val streamingThinking: StateFlow<String> = _streamingThinking.asStateFlow()
    val quickMode: StateFlow<Boolean> = _quickMode.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val notice: StateFlow<String?> = _notice.asStateFlow()
    val thoughts: StateFlow<List<ThoughtLog.Entry>> = _thoughts.asStateFlow()
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()
    val attachments: StateFlow<List<ImageAttachment>> = _attachments.asStateFlow()
    val pendingToolCall = _pendingToolCall.asStateFlow()
    val counts: StateFlow<Triple<Int, Int, Int>> = _counts.asStateFlow()

    val facts: StateFlow<List<FactEntity>> =
        dao.observeFacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val personas =
        personaRepository.personas
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val first = dao.observeConversations().first().firstOrNull()
            if (first != null) _convId.value = first.id
            refreshThoughts()
        }
    }

    fun selectConversation(id: Long) {
        _convId.value = id
    }

    fun newConversation() {
        _convId.value = 0
    }

    fun setInput(text: String) {
        _input.value = text
    }

    fun toggleQuickMode() {
        _quickMode.value = !_quickMode.value
    }

    fun showNotice(msg: String) {
        _notice.value = msg
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty() || _sending.value) return
        val images = _attachments.value
        _input.value = ""
        _attachments.value = emptyList()
        doSend(text, images)
    }

    fun addAttachment(uri: android.net.Uri) {
        viewModelScope.launch {
            ImageStorage.persist(getApplication(), uri)?.let { image ->
                _attachments.value = _attachments.value + image
            } ?: showNotice("Не удалось прочитать изображение")
        }
    }

    fun removeAttachment(image: ImageAttachment) {
        _attachments.value = _attachments.value - image
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    fun approveTool() {
        viewModelScope.launch {
            val result = Dependencies.toolConfirmation.approve() ?: return@launch
            _pendingToolCall.value = null
            _notice.value = if (result.success) result.output else (result.error ?: "Инструмент завершился ошибкой")
        }
    }

    fun rejectTool() {
        Dependencies.toolConfirmation.dismiss()
        _pendingToolCall.value = null
    }

    fun selectPersona(id: Long) {
        viewModelScope.launch {
            personaRepository.select(id)
            personaRepository.selected()?.let { persona ->
                Dependencies.settings.setLora(persona.loraPath.orEmpty(), persona.loraScale)
            }
            _notice.value = "Персона переключена"
        }
    }

    fun sendVoice(text: String) {
        if (text.isBlank() || _sending.value) return
        doSend(text, emptyList())
    }

    fun regenerate() {
        if (_sending.value) return
        viewModelScope.launch {
            val id = _convId.value
            if (id == 0L) return@launch
            val recent = withContext(Dispatchers.IO) { dao.recentMessages(id, 1) }
            val last = recent.firstOrNull() ?: return@launch
            val lastUser =
                withContext(Dispatchers.IO) { dao.recentMessages(id, 20) }
                    .lastOrNull { it.role == "user" } ?: return@launch
            withContext(Dispatchers.IO) {
                dao.deleteLastAssistant(id)
                dao.insertMessage(
                    MessageEntity(conversationId = id, role = "assistant", content = "", status = "done")
                )
                dao.deleteLastAssistant(id)
            }
            if (last.role == "assistant") {
                withContext(Dispatchers.IO) { dao.deleteLastAssistant(id) }
            }
            _input.value = lastUser.content
            doSend(lastUser.content, emptyList())
        }
    }

    private fun doSend(text: String, images: List<ImageAttachment>) {
        viewModelScope.launch {
            val id = ensureConversation()
            _streaming.value = ""
            _error.value = null
            _notice.value = null
            _sending.value = true
            val predictLength = if (_quickMode.value) QUICK_PREDICT_LENGTH else null
            try {
                agent.send(id, text, predictLength, images).collect { event ->
                    when (event) {
                        is Agent.Event.Token -> _streaming.value += event.text
                        is Agent.Event.Thinking -> _streamingThinking.value += event.text
                        is Agent.Event.Done -> {
                            _streaming.value = ""
                            _streamingThinking.value = ""
                            speakIfEnabled(event.full)
                        }
                        is Agent.Event.Failure -> {
                            _streaming.value = ""
                            _streamingThinking.value = ""
                            _error.value = event.message
                        }
                        is Agent.Event.ToolRequired -> {
                            _pendingToolCall.value = event.call
                        }
                    }
                }
            } finally {
                _sending.value = false
                refreshThoughts()
            }
        }
    }

    fun refreshThoughts() {
        viewModelScope.launch {
            val (pending, outbox, inbox) =
                withContext(Dispatchers.IO) {
                    Triple(
                        dao.countPendingOutbox(),
                        dao.countOutbox(),
                        dao.countInbox()
                    )
                }
            _counts.value = Triple(outbox, pending, inbox)
            _thoughts.value = ThoughtLog.entries()
        }
    }

    fun lastContext(): String = ThoughtLog.lastContext()

    fun syncNow() {
        viewModelScope.launch {
            _syncStatus.value = "Синхронизируем…"
            ThoughtLog.add(ThoughtLog.Tag.SYNC, "Запущен обмен с сетевым каналом")
            refreshThoughts()
            val report = Dependencies.syncCoordinator.syncNow()
            if (report.error == null) {
                _syncStatus.value = "Готово: отправлено ${report.pushed}, получено ${report.pulled}"
                ThoughtLog.add(ThoughtLog.Tag.SYNC, "Синхронизация: отправлено ${report.pushed}, получено ${report.pulled}")
            } else {
                _syncStatus.value = "Ошибка: ${report.error}"
                ThoughtLog.add(ThoughtLog.Tag.SYNC, "Синхронизация не удалась: ${report.error}")
            }
            refreshThoughts()
        }
    }

    private fun speakIfEnabled(text: String) {
        viewModelScope.launch {
            val s = Dependencies.settings.settings.first()
            if (s.ttsEnabled && text.isNotBlank()) {
                Tts.speak(getApplication(), text)
            }
        }
    }

    suspend fun exportConversation(): String? {
        val id = _convId.value
        if (id == 0L) return null
        val conv = currentConversation.value
        val list = withContext(Dispatchers.IO) { dao.allMessages(id) }
        if (list.isEmpty()) return null
        return buildString {
            appendLine("# ${conv?.title ?: "Беседа"}")
            list.forEach { m ->
                val who = if (m.role == "user") "Вы" else "AIIA"
                appendLine()
                appendLine("[$who]:")
                appendLine(m.content)
            }
        }.trimEnd('\n')
    }

    /** Exports every conversation plus the memory store as a versioned JSON document. */
    suspend fun exportBackup(): String? = withContext(Dispatchers.IO) {
        val conversations = dao.allConversations().map { conversation ->
            BackupConversation(
                title = conversation.title,
                pinned = conversation.pinned,
                createdAt = conversation.createdAt,
                messages = dao.allMessages(conversation.id).map {
                    BackupMessage(it.role, it.content, it.createdAt, it.reasoning)
                }
            )
        }
        val facts = dao.topFacts(2000).map {
            BackupFact(it.fact, it.category, it.favorite, it.createdAt)
        }
        val document = BackupDocument(
            exportedAt = System.currentTimeMillis(),
            conversations = conversations,
            facts = facts
        )
        if (BackupCodec.isEmpty(document)) null else BackupCodec.encode(document)
    }

    /** Accepts both the JSON backup format and the plain transcript produced by older builds. */
    fun importConversation(text: String) {
        viewModelScope.launch {
            val document = BackupCodec.decode(text)
            if (document != null) {
                importDocument(document)
                return@launch
            }
            val messages = LegacyTranscript.parse(text)
            if (messages.isEmpty()) {
                _notice.value = "Не удалось разобрать импортируемый файл."
                return@launch
            }
            val imported = restore(
                title = LegacyTranscript.title(text),
                messages = messages,
                conversation = BackupConversation(title = LegacyTranscript.title(text))
            )
            if (imported.first == 0L) {
                _notice.value = "Не удалось разобрать импортируемый файл."
                return@launch
            }
            _convId.value = imported.first
            _notice.value = "Импортировано сообщений: ${imported.second}"
        }
    }

    private suspend fun importDocument(document: BackupDocument) {
        var messages = 0
        var restoredFacts = 0
        document.conversations.forEach { conversation ->
            val result = restore(conversation.title, conversation.messages, conversation)
            if (result.first != 0L) messages += result.second
        }
        withContext(Dispatchers.IO) {
            for (fact in document.facts) {
                if (fact.fact.isNotBlank()) {
                    val createdAt = if (fact.createdAt > 0) fact.createdAt else System.currentTimeMillis()
                    if (dao.insertFactIfMissing(fact.fact, fact.category, fact.favorite, createdAt) > 0L) {
                        restoredFacts++
                    }
                }
            }
        }
        if (messages == 0 && restoredFacts == 0) {
            _notice.value = "В файле нет данных для импорта."
            return
        }
        _notice.value = buildString {
            append("Импортировано диалогов: ${document.conversations.size}, сообщений: $messages")
            if (restoredFacts > 0) append(", фактов: $restoredFacts")
        }
    }

    /** Returns the new conversation id and the number of stored messages. */
    private suspend fun restore(title: String, messages: List<BackupMessage>, conversation: BackupConversation): Pair<Long, Int> = withContext(Dispatchers.IO) {
        val usable = messages.filter { it.content.isNotBlank() }.take(MAX_IMPORT_MESSAGES)
        val id = if (usable.isEmpty()) {
            0L
        } else {
            dao.insertConversation(
                ConversationEntity(
                    title = title.ifBlank { "Импортированный диалог" }.take(80),
                    pinned = conversation.pinned,
                    createdAt = if (conversation.createdAt > 0) conversation.createdAt else System.currentTimeMillis()
                )
            )
        }
        usable.forEach {
            dao.insertMessage(
                MessageEntity(
                    conversationId = id,
                    role = it.role,
                    content = it.content,
                    createdAt = if (it.createdAt > 0) it.createdAt else System.currentTimeMillis(),
                    reasoning = it.reasoning
                )
            )
        }
        id to usable.size
    }

    fun outboxConversation() {
        viewModelScope.launch {
            val id = _convId.value
            if (id == 0L) return@launch
            val list = withContext(Dispatchers.IO) { dao.allMessages(id) }
            val existing = withContext(Dispatchers.IO) { dao.allOutbox() }.map { it.content }.toHashSet()
            var added = 0
            list.filter { it.role == "user" || it.role == "assistant" }
                .filter { it.content.isNotBlank() }
                .map { it.content }
                .forEach { content ->
                    if (existing.add(content)) {
                        withContext(Dispatchers.IO) {
                            dao.enqueueOutbox(
                                OutboxEntity(conversationId = id, content = content)
                            )
                        }
                        added++
                    }
                }
            _notice.value = if (added > 0) "В outbox добавлено: $added" else "Отправлять нечего."
        }
    }

    private suspend fun ensureConversation(): Long {
        if (_convId.value != 0L) return _convId.value
        val id =
            withContext(Dispatchers.IO) {
                dao.insertConversation(ConversationEntity())
            }
        _convId.value = id
        return id
    }

    companion object {
        private const val MAX_IMPORT_MESSAGES = 2000

        const val QUICK_PREDICT_LENGTH = 128
    }
}

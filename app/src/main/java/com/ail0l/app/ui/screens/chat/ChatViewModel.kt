package com.ail0l.app.ui.screens.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ail0l.app.agent.Agent
import com.ail0l.app.data.entities.ConversationEntity
import com.ail0l.app.data.entities.FactEntity
import com.ail0l.app.data.entities.MessageEntity
import com.ail0l.app.data.entities.OutboxEntity
import com.ail0l.app.dm.Dependencies
import com.ail0l.app.util.ThoughtLog
import com.ail0l.app.util.Tts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Dependencies.db.dao()
    private val agent: Agent = Dependencies.agent

    private val _convId = MutableStateFlow(0L)
    private val _input = MutableStateFlow("")
    private val _sending = MutableStateFlow(false)
    private val _streaming = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _notice = MutableStateFlow<String?>(null)
    private val _quickMode = MutableStateFlow(false)
    private val _thoughts = MutableStateFlow(ThoughtLog.entries())
    private val _syncStatus = MutableStateFlow<String?>(null)
    private val _counts = MutableStateFlow(Triple(0, 0, 0)) // outbox, pending, inbox

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
    val quickMode: StateFlow<Boolean> = _quickMode.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val notice: StateFlow<String?> = _notice.asStateFlow()
    val thoughts: StateFlow<List<ThoughtLog.Entry>> = _thoughts.asStateFlow()
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()
    val counts: StateFlow<Triple<Int, Int, Int>> = _counts.asStateFlow()

    val facts: StateFlow<List<FactEntity>> =
        dao.observeFacts()
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

    /** Переключает «быстрый ответ» — лимит генерируемых токенов на следующее обращение. */
    fun toggleQuickMode() {
        _quickMode.value = !_quickMode.value
    }

    fun showNotice(msg: String) {
        _notice.value = msg
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty() || _sending.value) return
        _input.value = ""
        doSend(text)
    }

    /** Отправка голосовой команды без изменения поля ввода. */
    fun sendVoice(text: String) {
        if (text.isBlank() || _sending.value) return
        doSend(text)
    }

    /** Удаляет последний ответ и повторяет генерацию по последнему запросу. */
    fun regenerate() {
        if (_sending.value) return
        viewModelScope.launch {
            val id = _convId.value
            if (id == 0L) return@launch
            val recent = withContext(Dispatchers.IO) { dao.recentMessages(id, 1) }
            val last = recent.firstOrNull() ?: return@launch
            val lastUser = withContext(Dispatchers.IO) { dao.recentMessages(id, 20) }
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
            doSend(lastUser.content)
        }
    }

    private fun doSend(text: String) {
        viewModelScope.launch {
            val id = ensureConversation()
            _streaming.value = ""
            _error.value = null
            _notice.value = null
            _sending.value = true
            val predictLength = if (_quickMode.value) QUICK_PREDICT_LENGTH else null
            try {
                agent.send(id, text, predictLength).collect { event ->
                    when (event) {
                        is Agent.Event.Token -> _streaming.value += event.text
                        is Agent.Event.Done -> {
                            _streaming.value = ""
                            speakIfEnabled(event.full)
                        }
                        is Agent.Event.Failure -> {
                            _streaming.value = ""
                            _error.value = event.message
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
            val (pending, outbox, inbox) = withContext(Dispatchers.IO) {
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

    /** Экспорт текущей беседы в текст для Share/файла. */
    suspend fun exportConversation(): String? {
        val id = _convId.value
        if (id == 0L) return null
        val conv = currentConversation.value
        val list = withContext(Dispatchers.IO) { dao.allMessages(id) }
        if (list.isEmpty()) return null
        return buildString {
            appendLine("# ${conv?.title ?: "Беседа"}")
            list.forEach { m ->
                val who = if (m.role == "user") "Вы" else "AIL0L"
                appendLine()
                appendLine("[$who]:")
                appendLine(m.content)
            }
        }.trimEnd('\n')
    }

    /** Импорт беседы из текста (строки «Вы: …» / «AIL0L: …», допускаются и «user:»/«assistant:»). */
    fun importConversation(text: String) {
        viewModelScope.launch {
            val lines = text.replace("\r", "").split('\n')
            val parsed = mutableListOf<Pair<String, String>>()
            var cur: Pair<String, String>? = null
            for (line in lines) {
                val match = IMPORT_PATTERN.find(line.trim())
                if (match != null) {
                    cur?.let { if (it.second.isNotBlank() || parsed.isEmpty()) parsed += it }
                    val role = if (match.groupValues[1].lowercase().contains("user")) "user" else "assistant"
                    cur = role to match.groupValues[2].trim()
                } else {
                    cur = cur?.let { it.first to (it.second + "\n" + line.trim()) }
                }
            }
            cur?.let { if (it.second.isNotBlank()) parsed += it }
            val relevant = parsed.filter { it.second.isNotBlank() }.take(200)
            if (relevant.isEmpty()) {
                _notice.value = "Не удалось разобрать импортируемый текст."
                return@launch
            }
            val id = withContext(Dispatchers.IO) {
                val cid = dao.insertConversation(ConversationEntity(title = relevant.first().second.take(30)))
                relevant.forEach { (role, content) ->
                    dao.insertMessage(MessageEntity(conversationId = cid, role = role, content = content))
                }
                cid
            }
            _convId.value = id
            _notice.value = "Импортировано сообщений: ${relevant.size}"
        }
    }

    /** Кладёт все сообщения беседы в outbox (без дублей по содержимому). */
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
        val id = withContext(Dispatchers.IO) {
            dao.insertConversation(ConversationEntity())
        }
        _convId.value = id
        return id
    }

    companion object {
        private val IMPORT_PATTERN = Regex("""\[?(Вы|AIL0L|user|assistant|бот|Бот)\]?\s*:""", RegexOption.IGNORE_CASE)

        /** «Быстрый ответ» — сколько токенов максимум генерировать. */
        const val QUICK_PREDICT_LENGTH = 128
    }
}
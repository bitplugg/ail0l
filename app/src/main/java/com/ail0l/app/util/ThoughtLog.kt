package com.ail0l.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Журнал «мыслей и размышлений» агента.
 *
 * Каждая запись — что реально происходило внутри: собранный контекст,
 * факты памяти, вызовы инструментов (веб-поиск, сетевой канал), генерация.
 * Вкладки в чате: мысли / память / контекст / инструменты / синхро.
 */
object ThoughtLog {

    enum class Tag { THINK, MEMORY, CONTEXT, TOOL, SYNC }

    data class Entry(
        val tag: Tag,
        val text: String,
        val at: Long = System.currentTimeMillis()
    )

    private val entries = CopyOnWriteArrayList<Entry>()
    private const val MAX_ENTRIES = 500

    private val _entriesFlow = MutableStateFlow<List<Entry>>(emptyList())

    /** Живой поток записей — для раздела «Мысли ИИ» в нижней навигации. */
    val entriesFlow: StateFlow<List<Entry>> = _entriesFlow.asStateFlow()

    @Volatile
    private var lastContext: String = ""

    fun add(tag: Tag, text: String) {
        entries.add(0, Entry(tag, text))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        _entriesFlow.value = entries.toList()
    }

    fun clear() {
        entries.clear()
        _entriesFlow.value = emptyList()
    }

    fun context(systemPrompt: String) {
        lastContext = systemPrompt
        add(Tag.CONTEXT, "Собран системный промпт (${systemPrompt.length} симв.)")
    }

    fun lastContext(): String = lastContext

    fun entries(): List<Entry> = entries.toList()

    /** Читаемая метка времени. */
    fun timeLabel(at: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
}
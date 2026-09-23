package com.ail0l.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Сообщение внутри диалога. role: user | assistant | system */
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "done" // done | streaming | error
)

/** Беседа с агентом. summary = сжатая память о прошлых разговорах */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "Новый диалог",
    val summary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Постоянный факт о пользователе — то, что агент «запоминает» навсегда */
@Entity(tableName = "facts", indices = [Index(value = ["fact"], unique = true)])
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String = "user",
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** Исходящее сообщение, ожидающее отправки через сетевой канал */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long? = null,
    val content: String,
    val toDevice: String = "",
    val status: String = "pending", // pending | sent | failed
    val createdAt: Long = System.currentTimeMillis()
)

/** Входящее сообщение, полученное из сетевого канала */
@Entity(tableName = "inbox", indices = [Index(value = ["remoteId"], unique = true)])
data class InboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String,
    val conversationId: Long? = null,
    val sender: String = "remote",
    val content: String,
    val read: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** Скачанная локальная модель (GGUF) */
@Entity(tableName = "models", indices = [Index(value = ["repo", "filename"], unique = true)])
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repo: String,
    val filename: String,
    val family: String,       // напр. Qwen2.5
    val paramsLabel: String,  // 0.5B / 1.5B / …
    val quant: String = "Q4_K_M",
    val sizeBytes: Long = 0,
    val modelFile: String = "",   // абсолютный путь к .gguf
    val installed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
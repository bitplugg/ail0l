package com.aiia.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val attachments: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "done"
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "Новый диалог",
    val summary: String = "",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "facts", indices = [Index(value = ["fact"], unique = true)])
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String = "user",
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long? = null,
    val content: String,
    val toDevice: String = "",
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis()
)

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

@Entity(tableName = "models", indices = [Index(value = ["repo", "filename"], unique = true)])
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repo: String,
    val filename: String,
    val family: String,
    val paramsLabel: String,
    val quant: String = "Q4_K_M",
    val sizeBytes: Long = 0,
    val modelFile: String = "",
    val installed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    val isTimer: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "personas", indices = [Index(value = ["name"], unique = true)])
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val systemPrompt: String,
    val loraPath: String? = null,
    val loraScale: Float = 1.0f,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "fact_embeddings", indices = [Index(value = ["factId"], unique = true)])
data class FactEmbeddingEntity(
    @PrimaryKey val factId: Long,
    val vector: String,
    val model: String = "onnx-384",
    val updatedAt: Long = System.currentTimeMillis()
)

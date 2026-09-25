package com.aiia.app.sync

import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.data.entities.InboxEntity
import com.aiia.app.data.entities.MessageEntity
import com.aiia.app.util.Crypto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object P2pReceiver {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun receive(
        db: AppDatabase,
        settings: SettingsRepository,
        body: String
    ): Int {
        val request = json.decodeFromString(PushRequest.serializer(), body)
        val config = settings.settings.first()
        val localId = config.syncDeviceId
        val accepted = request.messages.filter { it.toDevice.isNullOrBlank() || it.toDevice == localId }
        var count = 0
        accepted.forEach { message ->
            val text = if (config.syncPassword.isBlank()) message.content
            else Crypto.decrypt(config.syncPassword, message.content) ?: return@forEach
            val inserted = db.dao().insertInbox(
                InboxEntity(
                    remoteId = message.id,
                    conversationId = message.conversationId,
                    sender = message.sender,
                    content = text,
                    createdAt = message.createdAt
                )
            )
            if (inserted != -1L) {
                message.conversationId?.let { room ->
                    db.dao().insertMessage(MessageEntity(
                        conversationId = room,
                        role = "assistant",
                        content = text,
                        createdAt = message.createdAt
                    ))
                }
                count++
            }
        }
        return count
    }
}

package com.ail0l.app.sync

import com.ail0l.app.data.AppDatabase
import com.ail0l.app.data.SettingsRepository
import com.ail0l.app.data.entities.InboxEntity
import com.ail0l.app.data.entities.MessageEntity
import com.ail0l.app.data.entities.OutboxEntity
import com.ail0l.app.util.Crypto
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val error: String? = null
)

/**
 * Координатор сетевого канала:
 * — отправляет накопленные сообщения из outbox;
 * — забирает новые входящие (ответы с других устройств) в inbox и в беседы;
 * — идемпотентен: повторный pull не дублирует (unique-индекс по remoteId).
 */
class SyncCoordinator(
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository
) {

    private val cursorByServer = ConcurrentHashMap<String, Long>()

    suspend fun syncNow(): SyncReport {
        val settings = settingsRepo.settings.first()
        if (!settings.syncEnabled || settings.syncBaseUrl.isBlank()) {
            return SyncReport(error = "sync выключен")
        }

        val base = settings.syncBaseUrl.trimEnd('/')
        val device = settings.syncDeviceId
        if (device.isBlank()) return SyncReport(error = "не задан ID устройства")

        return try {
            val channel = MessagingChannel("$base", settings.syncPassword)
            val password = settings.syncPassword
            val encrypt: (String) -> String = { c ->
                if (password.isBlank()) c else Crypto.encrypt(password, c)
            }
            val decrypt: (String) -> String = { c ->
                when {
                    !Crypto.isEncrypted(c) -> c
                    password.isBlank() -> c
                    else -> Crypto.decrypt(password, c) ?: "🔒 не удалось расшифровать"
                }
            }
            var pushed = 0

            // push: pending и failed повторно
            val toSend = db.dao().pendingOutbox() + db.dao().failedOutbox()
            if (toSend.isNotEmpty()) {
                val wire = toSend.map {
                    WireMessage(
                        id = "local-${it.id}",
                        conversationId = it.conversationId,
                        sender = device,
                        content = encrypt(it.content),
                        toDevice = it.toDevice.ifBlank { null },
                        createdAt = it.createdAt
                    )
                }
                channel.push(device, wire)
                toSend.forEach { db.dao().markOutboxSent(it.id) }
                pushed = toSend.size
            }

            // pull
            val after = cursorByServer[base] ?: 0L
            val pulledList = channel.pull(device, after)

            pulledList.forEach { m ->
                val text = decrypt(m.content)
                db.dao().insertInbox(
                    InboxEntity(
                        remoteId = m.id,
                        conversationId = m.conversationId,
                        sender = m.sender,
                        content = text,
                        createdAt = m.createdAt
                    )
                )

                // если входящее адресовано в существующий у нас диалог — кладём сообщением
                val roomId = m.conversationId
                if (roomId != null && roomId in ourConversations()) {
                    db.dao().insertMessage(
                        MessageEntity(
                            conversationId = roomId,
                            role = "assistant",
                            content = text,
                            createdAt = m.createdAt
                        )
                    )
                }
                if (m.createdAt > after) cursorByServer[base] = m.createdAt
            }

            SyncReport(pushed = pushed, pulled = pulledList.size)
        } catch (e: Exception) {
            SyncReport(error = e.message)
        }
    }

    private suspend fun ourConversations(): Set<Long> =
        db.dao().observeConversations().first().mapTo(HashSet()) { it.id }
}
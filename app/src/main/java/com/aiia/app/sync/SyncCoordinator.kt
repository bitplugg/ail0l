package com.aiia.app.sync

import android.content.Context
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.data.entities.InboxEntity
import com.aiia.app.data.entities.MessageEntity
import com.aiia.app.data.entities.OutboxEntity
import com.aiia.app.util.Crypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val error: String? = null
)

class SyncCoordinator(
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val context: Context
) {

    private val cursorByServer = ConcurrentHashMap<String, Long>()

    suspend fun syncNow(): SyncReport {
        val settings = settingsRepo.settings.first()
        if (!settings.syncEnabled) return SyncReport(error = "sync выключен")
        val device = settings.syncDeviceId
        if (device.isBlank()) return SyncReport(error = "не задан ID устройства")
        val password = settings.syncPassword
        val encrypt: (String) -> String = { if (password.isBlank()) it else Crypto.encrypt(password, it) }
        val decrypt: (String) -> String = {
            when {
                !Crypto.isEncrypted(it) -> it
                password.isBlank() -> it
                else -> Crypto.decrypt(password, it) ?: "🔒 не удалось расшифровать"
            }
        }
        val toSend = db.dao().pendingOutbox() + db.dao().failedOutbox()
        var pushed = 0
        val sentIds = mutableSetOf<Long>()
        val pulled = mutableListOf<WireMessage>()
        return try {
            if (settings.p2pEnabled) {
                val nsd = NsdSyncManager(
                    context = context,
                    servicePort = settings.p2pPort,
                    deviceId = device
                )
                nsd.startAdvertising()
                val peers = nsd.discoverFor()
                val p2pChannel = MessagingChannel("", password)
                toSend.forEach { outbox ->
                    val targets = peers.filter { peer ->
                        outbox.toDevice.isBlank() || peer.deviceId == outbox.toDevice || peer.name == outbox.toDevice
                    }
                    targets.forEach { peer ->
                        runCatching {
                            p2pChannel.sendP2p(peer, listOf(outbox.toWire(device, encrypt)))
                            db.dao().markOutboxSent(outbox.id)
                            sentIds += outbox.id
                            pushed++
                        }.onFailure { db.dao().markOutboxFailed(outbox.id) }
                    }
                }
                nsd.stop()
            }
            val base = settings.syncBaseUrl.trimEnd('/')
            if (base.isNotBlank()) {
                val channel = MessagingChannel(base, password)
                val cloudCandidates = toSend.filter { it.id !in sentIds }
                val cloudMessages = cloudCandidates.map { it.toWire(device, encrypt) }
                if (cloudMessages.isNotEmpty()) {
                    channel.push(device, cloudMessages)
                    cloudCandidates.forEach { db.dao().markOutboxSent(it.id) }
                    pushed += cloudMessages.size
                }
                val after = cursorByServer[base] ?: 0L
                pulled += channel.pull(device, after)
                pulled.forEach { m ->
                    val text = decrypt(m.content)
                    val inserted = db.dao().insertInbox(InboxEntity(
                        remoteId = m.id,
                        conversationId = m.conversationId,
                        sender = m.sender,
                        content = text,
                        createdAt = m.createdAt
                    ))
                    val roomId = m.conversationId
                    if (inserted != -1L && roomId != null && roomId in ourConversations()) {
                        db.dao().insertMessage(MessageEntity(
                            conversationId = roomId,
                            role = "assistant",
                            content = text,
                            createdAt = m.createdAt
                        ))
                    }
                    if (m.createdAt > after) cursorByServer[base] = m.createdAt
                }
            }
            SyncReport(pushed = pushed, pulled = pulled.size)
        } catch (e: Exception) {
            SyncReport(error = e.message)
        }
    }

    private fun OutboxEntity.toWire(device: String, encrypt: (String) -> String): WireMessage = WireMessage(
        id = "local-$id",
        conversationId = conversationId,
        sender = device,
        content = encrypt(content),
        toDevice = toDevice.ifBlank { null },
        createdAt = createdAt
    )

    private suspend fun ourConversations(): Set<Long> =
        db.dao().observeConversations().first().mapTo(HashSet()) { it.id }
}

package com.ail0l.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ail0l.app.data.entities.ConversationEntity
import com.ail0l.app.data.entities.FactEntity
import com.ail0l.app.data.entities.InboxEntity
import com.ail0l.app.data.entities.MessageEntity
import com.ail0l.app.data.entities.ModelEntity
import com.ail0l.app.data.entities.OutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // ---- conversations ----
    @Insert
    suspend fun insertConversation(c: ConversationEntity): Long

    @Update
    suspend fun updateConversation(c: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String)

    @Query("UPDATE conversations SET summary = :summary, updatedAt = :at WHERE id = :id")
    suspend fun saveSummary(id: Long, summary: String, at: Long = System.currentTimeMillis())

    // ---- messages ----
    @Insert
    suspend fun insertMessage(m: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    suspend fun allMessages(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: Long)

    @Query("DELETE FROM messages WHERE role = 'assistant' AND id = (SELECT MAX(id) FROM messages WHERE conversationId = :conversationId)")
    suspend fun deleteLastAssistant(conversationId: Long)

    // ---- facts ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFact(f: FactEntity): Long

    @Query("SELECT * FROM facts ORDER BY favorite DESC, createdAt DESC")
    fun observeFacts(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE fact LIKE '%' || :q || '%' ORDER BY favorite DESC LIMIT 200")
    fun searchFacts(q: String): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE fact LIKE '%' || :q || '%' ORDER BY favorite DESC, createdAt DESC LIMIT 20")
    suspend fun findFacts(q: String): List<FactEntity>

    @Query("DELETE FROM facts WHERE createdAt < :before AND favorite = 0")
    suspend fun deleteOldFacts(before: Long): Int

    @Query("DELETE FROM facts")
    suspend fun clearFacts()

    @Query("UPDATE facts SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Delete
    suspend fun deleteFact(f: FactEntity)

    @Query("SELECT * FROM facts LIMIT :limit")
    suspend fun topFacts(limit: Int): List<FactEntity>

    // ---- outbox ----
    @Insert
    suspend fun enqueueOutbox(o: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY id")
    suspend fun pendingOutbox(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun allOutbox(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'pending'")
    suspend fun countPendingOutbox(): Int

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun countOutbox(): Int

    @Query("SELECT * FROM outbox WHERE status = 'failed' ORDER BY id")
    suspend fun failedOutbox(): List<OutboxEntity>

    @Query("UPDATE outbox SET status = 'sent' WHERE id = :id")
    suspend fun markOutboxSent(id: Long)

    @Query("UPDATE outbox SET status = 'failed' WHERE id = :id")
    suspend fun markOutboxFailed(id: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteOutbox(id: Long)

    @Query("DELETE FROM outbox WHERE status = 'sent' AND createdAt < :before")
    suspend fun pruneOutbox(before: Long): Int

    // ---- inbox ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInbox(i: InboxEntity)

    @Query("SELECT * FROM inbox ORDER BY createdAt DESC LIMIT 200")
    fun observeInbox(): Flow<List<InboxEntity>>

    @Query("SELECT COUNT(*) FROM inbox")
    suspend fun countInbox(): Int

    @Query("SELECT * FROM inbox WHERE read = 0 ORDER BY createdAt DESC")
    suspend fun unreadInbox(): List<InboxEntity>

    @Query("UPDATE inbox SET read = 1 WHERE id IN (:ids)")
    suspend fun markInboxRead(ids: List<Long>)

    @Query("DELETE FROM inbox WHERE id = :id")
    suspend fun deleteInbox(id: Long)

    @Insert
    suspend fun insertMessageFromInbox(m: MessageEntity)

    // ---- models ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(m: ModelEntity)

    @Query("SELECT * FROM models ORDER BY paramsLabel")
    fun observeModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE installed = 1 ORDER BY paramsLabel DESC LIMIT 1")
    fun observeInstalledModel(): Flow<ModelEntity?>

    @Query("SELECT * FROM models WHERE installed = 1 ORDER BY paramsLabel DESC LIMIT 1")
    suspend fun installedModel(): ModelEntity?

    @Query("UPDATE models SET modelFile = :path, installed = 1, sizeBytes = :size WHERE id = :id")
    suspend fun markInstalled(id: Long, path: String, size: Long)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteModel(id: Long)
}
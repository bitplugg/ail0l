package com.aiia.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aiia.app.data.entities.ConversationEntity
import com.aiia.app.data.entities.FactEntity
import com.aiia.app.data.entities.InboxEntity
import com.aiia.app.data.entities.MessageEntity
import com.aiia.app.data.entities.ModelEntity
import com.aiia.app.data.entities.OutboxEntity
import com.aiia.app.data.entities.ReminderEntity
import com.aiia.app.data.entities.PersonaEntity
import com.aiia.app.data.entities.FactEmbeddingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

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

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

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

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :q || '%' ORDER BY id DESC LIMIT :limit")
    suspend fun searchMessages(q: String, limit: Int = 30): List<MessageEntity>

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

    @Query("SELECT * FROM facts WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC LIMIT 100")
    suspend fun factsBetween(from: Long, to: Long): List<FactEntity>

    @Query("DELETE FROM facts")
    suspend fun clearFacts()

    @Query("UPDATE facts SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Update
    suspend fun updateFact(f: FactEntity)

    @Delete
    suspend fun deleteFact(f: FactEntity)

    @Query("SELECT * FROM facts LIMIT :limit")
    suspend fun topFacts(limit: Int): List<FactEntity>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInbox(i: InboxEntity): Long

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

    @Insert
    suspend fun insertReminder(r: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE triggerAt > :now ORDER BY triggerAt LIMIT 100")
    suspend fun upcomingReminders(now: Long = System.currentTimeMillis()): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminderById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders ORDER BY triggerAt LIMIT 100")
    suspend fun allReminders(): List<ReminderEntity>

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("SELECT * FROM personas ORDER BY isDefault DESC, name COLLATE NOCASE")
    fun observePersonas(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id LIMIT 1")
    suspend fun persona(id: Long): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersona(persona: PersonaEntity): Long

    @Delete
    suspend fun deletePersona(persona: PersonaEntity)

    @Query("SELECT * FROM fact_embeddings WHERE factId = :factId LIMIT 1")
    suspend fun embedding(factId: Long): FactEmbeddingEntity?

    @Query("SELECT * FROM fact_embeddings")
    suspend fun allEmbeddings(): List<FactEmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(embedding: FactEmbeddingEntity)

    @Query("DELETE FROM fact_embeddings WHERE factId = :factId")
    suspend fun deleteEmbedding(factId: Long)

    @Query("DELETE FROM fact_embeddings")
    suspend fun clearEmbeddings()
}

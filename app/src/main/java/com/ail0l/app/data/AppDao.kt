package com.ail0l.app.data

import androidx.room.*
import com.ail0l.app.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert suspend fun insertConversation(c: ConversationEntity): Long
    @Update suspend fun updateConversation(c: ConversationEntity)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") fun observeConversation(id: Long): Flow<ConversationEntity?>
    @Query("UPDATE conversations SET title = :title WHERE id = :id") suspend fun renameConversation(id: Long, title: String)
    @Query("UPDATE conversations SET summary = :summary, updatedAt = :at WHERE id = :id") suspend fun saveSummary(id: Long, summary: String, at: Long = System.currentTimeMillis())
    @Insert suspend fun insertMessage(m: MessageEntity): Long
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id") fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id") suspend fun allMessages(conversationId: Long): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit") suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity>
    @Query("DELETE FROM messages WHERE conversationId = :conversationId") suspend fun clearMessages(conversationId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFact(f: FactEntity): Long
    @Query("SELECT * FROM facts ORDER BY favorite DESC, createdAt DESC") fun observeFacts(): Flow<List<FactEntity>>
    @Query("SELECT * FROM facts LIMIT :limit") suspend fun topFacts(limit: Int): List<FactEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEmbedding(e: FactEmbeddingEntity)
    @Query("SELECT * FROM fact_embeddings") suspend fun allEmbeddings(): List<FactEmbeddingEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPersona(p: PersonaEntity): Long
    @Query("SELECT * FROM personas WHERE enabled = 1 ORDER BY name") fun observePersonas(): Flow<List<PersonaEntity>>
    @Query("SELECT * FROM personas WHERE id = :id") suspend fun persona(id: Long): PersonaEntity?
    @Query("UPDATE personas SET enabled = CASE WHEN id = :id THEN 1 ELSE 0 END") suspend fun selectPersona(id: Long)
    @Query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY id") suspend fun pendingOutbox(): List<OutboxEntity>
    @Query("UPDATE outbox SET status = 'sent' WHERE id = :id") suspend fun markOutboxSent(id: Long)
    @Query("UPDATE outbox SET status = 'failed' WHERE id = :id") suspend fun markOutboxFailed(id: Long)
    @Query("DELETE FROM outbox WHERE status = 'sent' AND createdAt < :before") suspend fun pruneOutbox(before: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInbox(i: InboxEntity)
    @Query("SELECT * FROM inbox WHERE read = 0 ORDER BY createdAt DESC") suspend fun unreadInbox(): List<InboxEntity>
    @Query("UPDATE inbox SET read = 1 WHERE id IN (:ids)") suspend fun markInboxRead(ids: List<Long>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertModel(m: ModelEntity)
    @Query("SELECT * FROM models ORDER BY paramsLabel") fun observeModels(): Flow<List<ModelEntity>>
    @Query("SELECT * FROM models WHERE installed = 1 ORDER BY paramsLabel DESC LIMIT 1") suspend fun installedModel(): ModelEntity?
}

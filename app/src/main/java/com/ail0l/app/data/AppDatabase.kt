package com.ail0l.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.ail0l.app.data.entities.*

@Database(entities = [ConversationEntity::class, MessageEntity::class, FactEntity::class, OutboxEntity::class, InboxEntity::class, ModelEntity::class, PersonaEntity::class, FactEmbeddingEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE outbox ADD COLUMN toDevice TEXT NOT NULL DEFAULT ''") } }
        private val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS personas (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, systemPrompt TEXT NOT NULL, loraPath TEXT, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL)"); db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_personas_name ON personas(name)") } }
        private val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS fact_embeddings (factId INTEGER NOT NULL PRIMARY KEY, vector BLOB NOT NULL, dimensions INTEGER NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL)"); db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_fact_embeddings_factId ON fact_embeddings(factId)") } }
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) { INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ail0l.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration().build().also { INSTANCE = it } }
    }
}

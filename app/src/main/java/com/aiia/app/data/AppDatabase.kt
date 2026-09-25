package com.aiia.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiia.app.data.entities.ConversationEntity
import com.aiia.app.data.entities.FactEntity
import com.aiia.app.data.entities.InboxEntity
import com.aiia.app.data.entities.MessageEntity
import com.aiia.app.data.entities.ModelEntity
import com.aiia.app.data.entities.OutboxEntity
import com.aiia.app.data.entities.ReminderEntity
import com.aiia.app.data.entities.PersonaEntity
import com.aiia.app.data.entities.FactEmbeddingEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        FactEntity::class,
        OutboxEntity::class,
        InboxEntity::class,
        ModelEntity::class,
        ReminderEntity::class,
        PersonaEntity::class,
        FactEmbeddingEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN toDevice TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS reminders (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "text TEXT NOT NULL, " +
                        "triggerAt INTEGER NOT NULL, " +
                        "isTimer INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS personas (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "systemPrompt TEXT NOT NULL, " +
                        "loraPath TEXT, " +
                        "loraScale REAL NOT NULL DEFAULT 1.0, " +
                        "isDefault INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_personas_name ON personas(name)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS fact_embeddings (" +
                        "factId INTEGER NOT NULL PRIMARY KEY, " +
                        "vector TEXT NOT NULL, " +
                        "model TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_fact_embeddings_factId ON fact_embeddings(factId)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aiia.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
    }
}

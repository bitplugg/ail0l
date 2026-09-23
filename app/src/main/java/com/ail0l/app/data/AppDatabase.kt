package com.ail0l.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ail0l.app.data.entities.ConversationEntity
import com.ail0l.app.data.entities.FactEntity
import com.ail0l.app.data.entities.InboxEntity
import com.ail0l.app.data.entities.MessageEntity
import com.ail0l.app.data.entities.ModelEntity
import com.ail0l.app.data.entities.OutboxEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        FactEntity::class,
        OutboxEntity::class,
        InboxEntity::class,
        ModelEntity::class
    ],
    version = 2,
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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ail0l.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
package com.ail0l.app.dm

import android.content.Context
import com.ail0l.app.agent.Agent
import com.ail0l.app.ai.download.ProvisionManager
import com.ail0l.app.ai.engines.EngineFactory
import com.ail0l.app.data.AppDatabase
import com.ail0l.app.data.SettingsRepository
import com.ail0l.app.memory.MemoryManager
import com.ail0l.app.sync.SyncCoordinator

/** Простой сервис-локатор (без внедрения зависимостей). */
object Dependencies {

    lateinit var appContext: Context
        private set

    lateinit var db: AppDatabase
        private set

    lateinit var settings: SettingsRepository
        private set

    lateinit var engineFactory: EngineFactory
        private set

    lateinit var memory: MemoryManager
        private set

    lateinit var agent: Agent
        private set

    lateinit var syncCoordinator: SyncCoordinator
        private set

    lateinit var provision: ProvisionManager
        private set

    fun init(context: Context) {
        appContext = context.applicationContext

        db = AppDatabase.get(appContext)
        settings = SettingsRepository(appContext)
        engineFactory = EngineFactory(appContext)
        memory = MemoryManager(db)
        syncCoordinator = SyncCoordinator(db, settings)
        agent = Agent(db, settings, engineFactory, memory)
        provision = ProvisionManager(
            context = appContext,
            db = db,
            settings = settings
        )
    }
}
package com.aiia.app.dm

import android.content.Context
import com.aiia.app.agent.Agent
import com.aiia.app.agent.ContextCacheManager
import com.aiia.app.agent.VectorSearchEngine
import com.aiia.app.agent.tools.SystemToolExecutor
import com.aiia.app.agent.tools.SystemToolExecutorHolder
import com.aiia.app.agent.tools.ToolConfirmationCoordinator
import com.aiia.app.ai.download.ProvisionManager
import com.aiia.app.ai.embeddings.EmbeddingProvisioner
import com.aiia.app.ai.engines.EngineFactory
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.memory.MemoryManager
import com.aiia.app.persona.PersonaRepository
import com.aiia.app.plugins.engine.PluginManager
import com.aiia.app.plugins.mcp.McpManager
import com.aiia.app.sync.SyncCoordinator
import com.aiia.app.util.KeystoreSecretBox

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

    lateinit var personas: PersonaRepository
        private set

    lateinit var contextCache: ContextCacheManager
        private set

    lateinit var toolConfirmation: ToolConfirmationCoordinator
        private set

    lateinit var vectorSearch: VectorSearchEngine
    lateinit var embeddings: EmbeddingProvisioner
        private set

    lateinit var mcp: McpManager
        private set

    lateinit var plugins: PluginManager
        private set

    fun init(context: Context) {
        appContext = context.applicationContext

        db = AppDatabase.get(appContext)
        settings = SettingsRepository(appContext, KeystoreSecretBox.createOrPlaintext())
        contextCache = ContextCacheManager(appContext)
        engineFactory = EngineFactory(appContext, contextCache)
        memory = MemoryManager(db)
        personas = PersonaRepository(db, settings)
        vectorSearch = VectorSearchEngine(db, settings)
        mcp = McpManager()
        plugins = PluginManager(appContext)
        SystemToolExecutorHolder.executor = SystemToolExecutor(appContext)
        toolConfirmation = ToolConfirmationCoordinator(mcp)
        syncCoordinator = SyncCoordinator(db, settings, appContext)
        agent =
            Agent(
                db = db,
                settingsRepo = settings,
                engineFactory = engineFactory,
                memory = memory,
                appContext = appContext,
                personaRepository = personas,
                toolConfirmation = toolConfirmation,
                vectorSearch = vectorSearch,
                mcp = mcp
            )
        embeddings =
            EmbeddingProvisioner(
                context = appContext,
                settings = settings,
                vectorSearch = vectorSearch
            )
        provision =
            ProvisionManager(
                context = appContext,
                db = db,
                settings = settings
            )
    }
}

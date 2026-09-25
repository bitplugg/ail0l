package com.aiia.app.persona

import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.data.entities.PersonaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PersonaRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    private val mutationMutex = Mutex()

    val personas: Flow<List<PersonaEntity>> = db.dao().observePersonas()

    suspend fun ensureDefault(): PersonaEntity = mutationMutex.withLock {
        val existing = db.dao().observePersonas().first().firstOrNull { it.isDefault }
        if (existing != null) return@withLock existing
        val default = PersonaEntity(
            name = "AIIA",
            systemPrompt = "Ты AIIA — дружелюбный и точный ИИ-напарник.",
            isDefault = true
        )
        val id = db.dao().upsertPersona(default)
        if (settings.settings.first().activePersonaId == 0L) settings.setActivePersona(id)
        db.dao().persona(id) ?: default.copy(id = id)
    }

    suspend fun save(persona: PersonaEntity): Long = mutationMutex.withLock {
        val id = db.dao().upsertPersona(persona)
        if (id > 0 && settings.settings.first().activePersonaId == 0L) {
            settings.setActivePersona(id)
        }
        id
    }

    suspend fun delete(persona: PersonaEntity) = mutationMutex.withLock {
        db.dao().deletePersona(persona)
        val current = settings.settings.first().activePersonaId
        if (current == persona.id) {
            val replacement = db.dao().observePersonas().first().firstOrNull()
            settings.setActivePersona(replacement?.id ?: 0L)
        }
    }

    suspend fun select(id: Long) = mutationMutex.withLock {
        if (id == 0L || db.dao().persona(id) != null) settings.setActivePersona(id)
    }

    suspend fun updateDefaultPrompt(prompt: String) = mutationMutex.withLock {
        db.dao().observePersonas().first().firstOrNull { it.isDefault }?.let {
            db.dao().upsertPersona(it.copy(systemPrompt = prompt))
        }
    }

    suspend fun selected(): PersonaEntity? {
        val id = settings.settings.first().activePersonaId
        return if (id == 0L) null else db.dao().persona(id)
    }
}

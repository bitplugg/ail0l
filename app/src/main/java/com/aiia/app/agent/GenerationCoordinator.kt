package com.aiia.app.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object GenerationCoordinator {
    private val mutex = Mutex()
    suspend fun <T> withGeneration(block: suspend () -> T): T = mutex.withLock { block() }
}

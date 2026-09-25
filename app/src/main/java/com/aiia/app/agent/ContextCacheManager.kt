package com.aiia.app.agent

import android.content.Context
import com.aiia.app.ai.LlamaEngine
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ContextCacheManager(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val metadataFile get() = File(appContext.filesDir, "aiia-kv-cache/meta.json")
    private val stateFile get() = File(appContext.filesDir, "aiia-kv-cache/context.bin")

    data class Key(val modelPath: String, val prompt: String, val nCtx: Int) {
        val digest: String
            get() = sha256("$modelPath\n$nCtx\n$prompt")
    }

    suspend fun restore(engine: LlamaEngine, key: Key): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!stateFile.isFile || !metadataFile.isFile) return@withContext false
            val expected = key.digest
            val actual = runCatching { metadataFile.readText().trim() }.getOrNull()
            if (actual != expected) return@withContext false
            engine.loadContextCache(stateFile.absolutePath)
        }
    }

    suspend fun save(engine: LlamaEngine, key: Key): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            stateFile.parentFile?.mkdirs()
            val ok = engine.saveContextCache(stateFile.absolutePath)
            if (ok) {
                val temp = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
                temp.writeText(key.digest)
                if (!temp.renameTo(metadataFile)) temp.copyTo(metadataFile, overwrite = true)
            }
            ok
        }
    }

    suspend fun restore(engine: InferenceEngine, key: Key): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!stateFile.isFile || !metadataFile.isFile) return@withContext false
            if (metadataFile.readText().trim() != key.digest) return@withContext false
            engine.loadContextCache(stateFile.absolutePath)
        }
    }

    suspend fun save(engine: InferenceEngine, key: Key): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            stateFile.parentFile?.mkdirs()
            val ok = engine.saveContextCache(stateFile.absolutePath)
            if (ok) {
                val temp = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
                temp.writeText(key.digest)
                if (!temp.renameTo(metadataFile)) temp.copyTo(metadataFile, overwrite = true)
            }
            ok
        }
    }

    fun invalidate() {
        stateFile.delete()
        metadataFile.delete()
    }

    companion object {
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

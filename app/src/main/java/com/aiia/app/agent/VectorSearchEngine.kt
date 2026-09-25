package com.aiia.app.agent

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.data.entities.FactEmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.LongBuffer
import kotlin.math.sqrt

class VectorSearchEngine(
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var sessionPath: String = ""

    suspend fun loadConfiguredModel(): Boolean = withContext(Dispatchers.IO) {
        val path = settings.settings.first().embeddingModelPath
        if (path.isBlank() || !java.io.File(path).isFile) return@withContext false
        if (session != null && sessionPath == path) return@withContext true
        session?.close()
        session = runCatching {
            val optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val options = optionsClass.getDeclaredConstructor().newInstance()
            val constructor = Class.forName("ai.onnxruntime.OrtSession").getConstructor(
                OrtEnvironment::class.java,
                String::class.java,
                optionsClass
            )
            constructor.newInstance(environment, path, options).also { sessionPath = path } as OrtSession
        }.getOrNull()
        session != null
    }

    suspend fun ensureIndexed(): Int = withContext(Dispatchers.IO) {
        val facts = db.dao().topFacts(10_000)
        var added = 0
        facts.forEach { fact ->
            if (db.dao().embedding(fact.id) == null) {
                upsert(fact.id, embed(fact.fact))
                added++
            }
        }
        added
    }

    suspend fun search(query: String, topK: Int = 5): List<ScoredFact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        loadConfiguredModel()
        val queryVector = embed(query)
        val facts = db.dao().topFacts(10_000).associateBy { it.id }
        facts.values.mapNotNull { fact ->
            val stored = db.dao().embedding(fact.id)
            val vector = if (stored == null) embed(fact.fact).also { upsert(fact.id, it) } else decode(stored.vector)
            ScoredFact(fact.fact, cosineSimilarity(queryVector, vector))
        }.sortedByDescending { it.score }.take(topK.coerceIn(1, 20))
    }

    suspend fun context(query: String, topK: Int = 5): String = search(query, topK)
        .joinToString("\n") { "- ${it.fact}" }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until size) {
            dot += a[i].toDouble() * b[i]
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    private fun embed(text: String): FloatArray {
        val active = session
        if (active != null) {
            runCatching {
                val ids = tokenize(text)
                val shape = longArrayOf(1, ids.size.toLong())
                val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
                val create = tensorClass.getMethod(
                    "createTensor",
                    OrtEnvironment::class.java,
                    java.nio.Buffer::class.java,
                    LongArray::class.java
                )
                val idTensor = create.invoke(null, environment, LongBuffer.wrap(ids), shape)
                val maskTensor = create.invoke(null, environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
                val run = active.javaClass.getMethod("run", Map::class.java)
                val result = run.invoke(active, mapOf("input_ids" to idTensor, "attention_mask" to maskTensor))
                val close = result.javaClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
                val value = result.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(result, 0)
                val vector = when (value) {
                    is FloatArray -> value
                    is Array<*> -> (value.firstOrNull() as? FloatArray) ?: FloatArray(0)
                    is List<*> -> (value.firstOrNull() as? FloatArray) ?: FloatArray(0)
                    else -> FloatArray(0)
                }
                close?.invoke(result)
                if (vector.isNotEmpty()) return normalize(vector.copyOf(384))
            }
        }
        return normalize(hashEmbedding(text, 384))
    }

    private fun tokenize(text: String): LongArray {
        val bytes = text.lowercase().toByteArray(Charsets.UTF_8)
        val ids = bytes.map { (it.toLong() and 0xffL) + 1L }.toLongArray()
        return if (ids.isEmpty()) longArrayOf(1L) else ids
    }

    private fun hashEmbedding(text: String, dimensions: Int): FloatArray = FloatArray(dimensions).also { out ->
        text.lowercase().toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
            val slot = ((byte.toInt() and 0xff) * 31 + index) % dimensions
            out[slot] += if (index % 2 == 0) 1f else -1f
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
        return vector
    }

    private fun encode(vector: FloatArray): String = vector.joinToString(",") { it.toString() }

    private fun decode(value: String): FloatArray = value.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()

    private suspend fun upsert(factId: Long, vector: FloatArray) {
        db.dao().upsertEmbedding(FactEmbeddingEntity(factId, encode(vector)))
    }

    data class ScoredFact(val fact: String, val score: Double)
}

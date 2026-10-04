package com.aiia.app.agent

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.aiia.app.data.AppDatabase
import com.aiia.app.data.SettingsRepository
import com.aiia.app.data.entities.FactEmbeddingEntity
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class VectorSearchEngine(
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var vocabulary: WordPieceTokenizer? = null
    private var hiddenSize: Int = 0
    private var maxInputTokens: Int = WordPieceTokenizer.DEFAULT_MAX_TOKENS
    private var tokenizerDirty: Boolean = false
    private var lastEmbedError: String? = null
    private var dimensions: Int = VectorMath.DEFAULT_DIMENSIONS
    private var sessionPath: String = ""

    suspend fun loadConfiguredModel(): Boolean = withContext(Dispatchers.IO) {
        val current = settings.settings.first()
        val path = current.embeddingModelPath
        dimensions = current.embeddingDimensions
        if (path.isBlank() || !java.io.File(path).isFile) {
            unload()
            return@withContext false
        }
        if (session != null && sessionPath == path) return@withContext true
        unload()

        val loaded =
            runCatching {
                val optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
                val options = optionsClass.getDeclaredConstructor().newInstance()
                val constructor =
                    Class.forName("ai.onnxruntime.OrtSession").getConstructor(
                        OrtEnvironment::class.java,
                        String::class.java,
                        optionsClass
                    )
                constructor.newInstance(environment, path, options) as OrtSession
            }.getOrNull() ?: return@withContext false

        session = loaded
        sessionPath = path
        lastEmbedError = null
        hiddenSize = declaredHiddenSize(loaded)
        maxInputTokens = WordPieceTokenizer.DEFAULT_MAX_TOKENS
        // A vocabulary next to the model turns the byte-hash fallback into real embeddings.
        vocabulary = loadVocabulary(path) ?: run {
            lastEmbedError = "Рядом с моделью нет vocab.txt: эмбеддинги будут приблизительными"
            null
        }
        tokenizerDirty = vocabulary != null
        loaded != null
    }

    private fun unload() {
        session?.close()
        session = null
        sessionPath = ""
        vocabulary = null
        hiddenSize = 0
    }

    /** Drops stored vectors when the model or its width changed, otherwise they stop matching. */
    suspend fun reindexIfStale(): Int {
        if (!tokenizerDirty) return 0
        tokenizerDirty = false
        val facts = db.dao().topFacts(10_000)
        withContext(Dispatchers.IO) { facts.forEach { upsert(it.id, embed(it.fact)) } }
        return facts.size
    }

    private fun loadVocabulary(modelPath: String): WordPieceTokenizer? {
        val file = java.io.File(modelPath)
        val candidates =
            listOf("vocab.txt", "tokenizer.json", "spiece.model", "vocab.json").map { java.io.File(file.parentFile, it) }
        val vocabFile = candidates.firstOrNull { it.isFile && it.name == "vocab.txt" } ?: return null
        val text = runCatching { vocabFile.readText() }.getOrNull() ?: return null
        return WordPieceTokenizer.fromVocabText(text)
    }

    private fun declaredHiddenSize(active: OrtSession): Int {
        val info = runCatching {
            active.javaClass.getMethod("getOutputInfo").invoke(active) as? Map<*, *>
        }.getOrNull() ?: return 0
        val node = info.values.firstOrNull() ?: return 0
        val type = runCatching { node.javaClass.getMethod("getInfo").invoke(node) }.getOrNull() ?: return 0
        val shape = runCatching {
            type.javaClass.getMethod("getShape").invoke(type) as? LongArray
        }.getOrNull() ?: return 0
        return shape.lastOrNull()?.toInt()?.takeIf { it in 1..4096 } ?: 0
    }

    fun lastEmbeddingError(): String? = lastEmbedError

    fun usesRealModel(): Boolean = session != null && vocabulary != null

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

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double = VectorMath.cosineSimilarity(a, b)

    private fun embed(text: String): FloatArray {
        val real = modelVector(text)
        if (real != null) return real
        return VectorMath.normalize(VectorMath.hashEmbedding(text, dimensions))
    }

    /** Returns null when no session is ready or the inference failed; the caller then hashes. */
    private fun modelVector(text: String): FloatArray? {
        val active = session ?: return null
        val tokenizer = vocabulary ?: return null
        return try {
            val encoding = tokenizer.encode(text, maxInputTokens)
            val shape = longArrayOf(1, encoding.length.toLong())
            val inputs = buildInputs(active, encoding, shape)
            val flat = runInference(active, inputs)
            if (flat.isEmpty()) {
                null
            } else {
                val pooled = VectorMath.poolModelOutput(flat, encoding.length, encoding.attentionMask, hiddenSize)
                tokenizerDirty = false
                VectorMath.normalize(VectorMath.fitDimensions(pooled, dimensions).copyOf())
            }
        } catch (e: Exception) {
            lastEmbedError = e.message ?: e::class.java.simpleName
            null
        }
    }

    private fun buildInputs(active: OrtSession, encoding: WordPieceTokenizer.Encoding, shape: LongArray): Map<String, Any> {
        // Exported graphs disagree on which inputs they declare, so feed only the names the
        // session actually declares instead of guessing.
        val declared = sessionInputNames(active)
        val inputs = HashMap<String, Any>()
        if (declared.isEmpty() || "input_ids" in declared) {
            inputs["input_ids"] = createTensor(encoding.inputIds, shape)
        }
        if (declared.isEmpty() || "attention_mask" in declared) {
            inputs["attention_mask"] = createTensor(encoding.attentionMask, shape)
        }
        if ("token_type_ids" in declared) {
            inputs["token_type_ids"] = createTensor(encoding.tokenTypeIds, shape)
        }
        if (inputs.isEmpty()) throw IllegalStateException("Модель не объявляет входов ввода")
        return inputs
    }

    private fun runInference(active: OrtSession, inputs: Map<String, Any>): FloatArray {
        val result = active.javaClass.getMethod("run", Map::class.java).invoke(active, inputs)
        return try {
            val raw = result.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(result, 0)
            VectorMath.unwrapOutput(raw)
        } finally {
            result.javaClass.methods
                .firstOrNull { it.name == "close" && it.parameterCount == 0 }
                ?.invoke(result)
        }
    }

    /** Creates an int64 tensor; ORT only exposes that overload through reflection here. */
    private fun createTensor(values: LongArray, shape: LongArray): Any {
        val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
        val create = tensorClass.getMethod(
            "createTensor",
            OrtEnvironment::class.java,
            java.nio.Buffer::class.java,
            LongArray::class.java
        )
        return create.invoke(null, environment, LongBuffer.wrap(values), shape)
    }

    private fun sessionInputNames(active: OrtSession): Set<String> = runCatching {
        val info = active.javaClass.getMethod("getInputInfo").invoke(active) as? Map<*, *> ?: return emptySet()
        info.keys.mapNotNull { it as? String }.toSet()
    }.getOrElse { emptySet() }

    private fun encode(vector: FloatArray): String = VectorMath.encode(vector)

    private fun decode(value: String): FloatArray = VectorMath.decode(value)

    private suspend fun upsert(factId: Long, vector: FloatArray) {
        db.dao().upsertEmbedding(FactEmbeddingEntity(factId, encode(vector)))
    }

    data class ScoredFact(val fact: String, val score: Double)
}

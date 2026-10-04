package com.aiia.app.agent

import kotlin.math.sqrt

/**
 * Pure vector helpers for the RAG index, kept free of Android and ONNX dependencies so the
 * scoring path stays unit-testable.
 */
object VectorMath {
    const val DEFAULT_DIMENSIONS = 384

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

    fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
        return vector
    }

    /**
     * Deterministic bag-of-bytes fallback used when no ONNX model is configured. It is not a
     * semantic embedding, but it keeps the index stable across processes so previously stored
     * vectors stay comparable.
     */
    fun hashEmbedding(text: String, dimensions: Int = DEFAULT_DIMENSIONS): FloatArray = FloatArray(dimensions).also { out ->
        text.lowercase().toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
            val slot = ((byte.toInt() and 0xff) * 31 + index) % dimensions
            out[slot] += if (index % 2 == 0) 1f else -1f
        }
    }

    fun encode(vector: FloatArray): String = vector.joinToString(",") { it.toString() }

    fun decode(value: String): FloatArray = value.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()

    /** Pads or truncates an ONNX output vector to [dimensions] so stored rows stay comparable. */
    fun fitDimensions(vector: FloatArray, dimensions: Int = DEFAULT_DIMENSIONS): FloatArray = when {
        vector.size == dimensions -> vector
        vector.size > dimensions -> vector.copyOf(dimensions)
        else -> FloatArray(dimensions).also { vector.copyInto(it) }
    }

    /**
     * Averages the token embeddings of a `[batch][seq][hidden]` output over the positions the
     * attention mask marks as real, which is what sentence-transformer checkpoints expect.
     * Returns an empty array when the tensor cannot be interpreted as a token matrix.
     */
    fun meanPool(hiddenStates: FloatArray, sequenceLength: Int, hiddenSize: Int, attentionMask: LongArray): FloatArray {
        if (sequenceLength <= 0 || hiddenSize <= 0) return FloatArray(0)
        if (hiddenStates.size < sequenceLength * hiddenSize) return FloatArray(0)

        val pooled = FloatArray(hiddenSize)
        var counted = 0
        for (token in 0 until sequenceLength) {
            if (token < attentionMask.size && attentionMask[token] == 0L) continue
            val offset = token * hiddenSize
            for (i in 0 until hiddenSize) pooled[i] += hiddenStates[offset + i]
            counted++
        }
        if (counted == 0) return FloatArray(0)
        for (i in 0 until hiddenSize) pooled[i] /= counted
        return pooled
    }

    /**
     * Unwraps the nested arrays and lists an ONNX Runtime result is handed back as, giving the
     * flat backing array of the first tensor.
     */
    fun unwrapOutput(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.firstOrNull()?.let { unwrapOutput(it) } ?: FloatArray(0)
        is List<*> -> value.firstOrNull()?.let { unwrapOutput(it) } ?: FloatArray(0)
        else -> FloatArray(0)
    }

    /**
     * Interprets a flat tensor as either a `[seq][hidden]` token matrix (mean pooled) or an
     * already pooled sentence vector. [hiddenSize] is only used for the first interpretation.
     */
    fun poolModelOutput(flat: FloatArray, sequenceLength: Int, attentionMask: LongArray, hiddenSize: Int = 0): FloatArray {
        if (flat.isEmpty()) return flat
        if (sequenceLength <= 1) return flat
        val hidden = hiddenSize.takeIf { it > 0 && flat.size == sequenceLength * it }
            ?: (flat.size / sequenceLength).takeIf { it > 0 && flat.size % sequenceLength == 0 }
            ?: return flat
        return meanPool(flat, sequenceLength, hidden, attentionMask)
    }
}

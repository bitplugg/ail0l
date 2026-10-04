package com.aiia.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {
    @Test
    fun `identical vectors score one`() {
        val vector = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1.0, VectorMath.cosineSimilarity(vector, vector), 1e-9)
    }

    @Test
    fun `orthogonal vectors score zero`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.0, VectorMath.cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `opposite vectors score minus one`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(-1.0, VectorMath.cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `zero and empty vectors do not divide by zero`() {
        assertEquals(0.0, VectorMath.cosineSimilarity(FloatArray(0), FloatArray(0)), 1e-9)
        assertEquals(0.0, VectorMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-9)
    }

    @Test
    fun `similarity is order preserving`() {
        val query = VectorMath.hashEmbedding("я люблю котов")
        val close = VectorMath.hashEmbedding("я люблю котов и собак")
        val far = VectorMath.hashEmbedding("совершенно другой текст про погоду")
        assertTrue(
            VectorMath.cosineSimilarity(query, close) > VectorMath.cosineSimilarity(query, far)
        )
    }

    @Test
    fun `mismatched lengths are compared over the common prefix`() {
        val a = floatArrayOf(1f, 0f, 5f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.0, VectorMath.cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `normalize produces unit length`() {
        val normalized = VectorMath.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
        assertEquals(1f, Math.hypot(normalized[0].toDouble(), normalized[1].toDouble()).toFloat(), 1e-6f)
    }

    @Test
    fun `normalize leaves a zero vector untouched`() {
        val zero = FloatArray(3)
        VectorMath.normalize(zero)
        assertTrue(zero.all { it == 0f })
    }

    @Test
    fun `hash embedding is deterministic and sized`() {
        val first = VectorMath.hashEmbedding("привет")
        val second = VectorMath.hashEmbedding("привет")
        assertTrue(first.contentEquals(second))
        assertEquals(VectorMath.DEFAULT_DIMENSIONS, first.size)
    }

    @Test
    fun `hash embedding reacts to text changes`() {
        val a = VectorMath.hashEmbedding("привет")
        val b = VectorMath.hashEmbedding("пока")
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `encode and decode round trip`() {
        val vector = floatArrayOf(0.5f, -0.25f, 0f)
        assertTrue(vector.contentEquals(VectorMath.decode(VectorMath.encode(vector))))
    }

    @Test
    fun `decode skips malformed values`() {
        assertTrue(floatArrayOf(1f, 2f).contentEquals(VectorMath.decode("1.0,2.0")))
        assertTrue(floatArrayOf(1f).contentEquals(VectorMath.decode("1.0,,oops")))
        assertEquals(0, VectorMath.decode("").size)
    }

    @Test
    fun `fitDimensions pads short vectors`() {
        val fitted = VectorMath.fitDimensions(floatArrayOf(1f, 2f), 4)
        assertEquals(4, fitted.size)
        assertEquals(1f, fitted[0], 1e-6f)
        assertEquals(2f, fitted[1], 1e-6f)
        assertEquals(0f, fitted[3], 1e-6f)
    }

    @Test
    fun `fitDimensions truncates long vectors`() {
        val fitted = VectorMath.fitDimensions(floatArrayOf(1f, 2f, 3f, 4f, 5f), 3)
        assertEquals(3, fitted.size)
        assertEquals(3f, fitted[2], 1e-6f)
    }

    @Test
    fun `mean pool averages only unmasked positions`() {
        val hidden = floatArrayOf(
            1f,
            0f,
            0f,
            2f,
            9f,
            9f
        )
        val mask = longArrayOf(1L, 1L, 0L)
        val pooled = VectorMath.meanPool(hidden, sequenceLength = 3, hiddenSize = 2, attentionMask = mask)
        assertEquals(0.5f, pooled[0], 1e-6f)
        assertEquals(1.0f, pooled[1], 1e-6f)
    }

    @Test
    fun `mean pool rejects inconsistent tensors`() {
        assertEquals(0, VectorMath.meanPool(floatArrayOf(1f, 2f), 0, 2, longArrayOf(1L)).size)
        assertEquals(0, VectorMath.meanPool(floatArrayOf(1f, 2f), 4, 2, longArrayOf(1L)).size)
        assertEquals(0, VectorMath.meanPool(floatArrayOf(1f, 2f), 2, 0, longArrayOf(1L)).size)
    }

    @Test
    fun `fully masked input pools to nothing`() {
        val pooled = VectorMath.meanPool(floatArrayOf(1f, 1f, 1f, 1f), 2, 2, longArrayOf(0L, 0L))
        assertEquals(0, pooled.size)
    }

    @Test
    fun `unwrap handles nested runtime shapes`() {
        val flat = floatArrayOf(1f, 2f, 3f)
        assertTrue(flat.contentEquals(VectorMath.unwrapOutput(flat)))
        assertTrue(flat.contentEquals(VectorMath.unwrapOutput(arrayOf(flat))))
        assertTrue(flat.contentEquals(VectorMath.unwrapOutput(listOf(flat))))
        assertEquals(0, VectorMath.unwrapOutput(null).size)
        assertEquals(0, VectorMath.unwrapOutput("nope").size)
    }

    @Test
    fun `pooling is applied to token matrices but not to pooled vectors`() {
        val tokens = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 1f)
        val mask = longArrayOf(1L, 1L, 1L, 1L)
        val pooled = VectorMath.poolModelOutput(tokens, 4, mask, hiddenSize = 2)
        assertEquals(2, pooled.size)
        assertEquals(0.5f, pooled[0], 1e-6f)
        assertEquals(0.5f, pooled[1], 1e-6f)

        val sentence = floatArrayOf(0.3f, 0.4f, 0.5f)
        assertTrue(sentence.contentEquals(VectorMath.poolModelOutput(sentence, 1, longArrayOf(1L), 3)))
    }
}

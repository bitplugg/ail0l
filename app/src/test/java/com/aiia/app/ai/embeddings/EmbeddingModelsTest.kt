package com.aiia.app.ai.embeddings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingModelsTest {

    @Test
    fun `catalog urls point at https resolvable paths`() {
        for (spec in EmbeddingModels.ALL) {
            assertTrue(spec.modelUrl.startsWith("https://huggingface.co/"))
            assertTrue(spec.vocabUrl.startsWith("https://huggingface.co/"))
            assertTrue(spec.modelUrl.endsWith(".onnx"))
            assertTrue(spec.vocabUrl.endsWith(".txt"))
            assertTrue("id must be safe for a directory name", spec.directoryName() == spec.id)
        }
    }

    @Test
    fun `specs declare a plausible size and vector width`() {
        for (spec in EmbeddingModels.ALL) {
            assertTrue(spec.sizeBytes > 1_000_000L)
            assertTrue(spec.dimensions in listOf(256, 384, 512, 768, 1024))
        }
    }

    @Test
    fun `ids are unique so the download directory cannot collide`() {
        val ids = EmbeddingModels.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `size is rendered in megabytes`() {
        assertEquals("129 МБ", EmbeddingModels.DEFAULT.displaySize())
    }

    @Test
    fun `default spec is part of the catalog`() {
        assertTrue(EmbeddingModels.ALL.contains(EmbeddingModels.DEFAULT))
    }
}

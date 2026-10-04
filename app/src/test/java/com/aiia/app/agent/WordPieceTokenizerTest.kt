package com.aiia.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPieceTokenizerTest {

    private val specials = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

    private val words =
        listOf(
            "hello", "world", "!", "a", "b", "c", "d",
            "привет", "мир", "##вет", "который", "##рый", "с", "д", "##д", "##друг", "##руг", "2024"
        )

    /** `fromVocabText` rejects tiny files, so the fixture is padded to look like a real vocabulary. */
    private fun tokenizer(): WordPieceTokenizer {
        val padding = (0 until 300).map { "filler$it" }
        val text = (specials + words + padding).joinToString("\n")
        val result = WordPieceTokenizer.fromVocabText(text)
        assertNotNull(result)
        return result!!
    }

    @Test
    fun `rejects files that are not vocabularies`() {
        assertNull(WordPieceTokenizer.fromVocabText(""))
        assertNull(WordPieceTokenizer.fromVocabText("hello\nworld"))
        assertNull(WordPieceTokenizer.fromVocabText((0 until 200).joinToString("\n") { "tok$it" }))
    }

    @Test
    fun `wraps ids in cls and sep with an all ones mask`() {
        val encoding = tokenizer().encode("hello world", maxTokens = 16)
        assertEquals(4, encoding.length)
        assertEquals(2L, encoding.inputIds[0])
        assertEquals(3L, encoding.inputIds[3])
        assertTrue(encoding.attentionMask.all { it == 1L })
        assertTrue(encoding.tokenTypeIds.all { it == 0L })
    }

    @Test
    fun `splits unknown words into word pieces`() {
        val encoding = tokenizer().encode("сдруг", maxTokens = 16)
        val pieces = encoding.inputIds.drop(1).dropLast(1)
        assertTrue("expected more than one piece, got $pieces", pieces.size >= 2)
    }

    @Test
    fun `unknown word falls back to the unk id`() {
        val tokenizer = tokenizer()
        val encoding = tokenizer.encode("zzz", maxTokens = 8)
        assertEquals(tokenizer.unknownToken.toLong(), encoding.inputIds[1])
    }

    @Test
    fun `known word stays a single piece`() {
        assertEquals(3, tokenizer().encode("мир", maxTokens = 16).length)
    }

    @Test
    fun `truncates to the token budget`() {
        val encoding = tokenizer().encode("hello world hello world hello world", maxTokens = 5)
        assertEquals(5, encoding.length)
        assertEquals(3L, encoding.inputIds.last())
    }

    @Test
    fun `short input is not padded so it cannot leak into the pooled vector`() {
        val tokenizer = tokenizer()
        val encoding = tokenizer.encode("hello", maxTokens = 8)
        assertEquals(3, encoding.length)
        assertTrue(encoding.inputIds.none { it == tokenizer.padToken.toLong() })
        assertTrue(encoding.attentionMask.all { it == 1L })
    }

    @Test
    fun `casing is folded and punctuation splits off`() {
        val tokenizer = tokenizer()
        val lower = tokenizer.encode("hello!", maxTokens = 16)
        val upper = tokenizer.encode("HELLO!", maxTokens = 16)
        assertTrue(lower.inputIds.contentEquals(upper.inputIds))
        assertEquals(4, lower.length)
    }

    @Test
    fun `empty input still produces the specials`() {
        val encoding = tokenizer().encode("   ", maxTokens = 6)
        assertEquals(2, encoding.length)
        assertEquals(2L, encoding.inputIds[0])
    }

    @Test
    fun `truncation budget leaves room for the specials`() {
        val encoding = tokenizer().encode("hello world hello world hello world", maxTokens = 8)
        assertTrue(encoding.length in 2..8)
        assertEquals(3L, encoding.inputIds.last())
    }
}

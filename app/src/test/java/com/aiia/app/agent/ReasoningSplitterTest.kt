package com.aiia.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningSplitterTest {

    private fun split(vararg chunks: String): Pair<String, String> {
        val splitter = ReasoningSplitter()
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        chunks.forEach { chunk ->
            splitter.accept(chunk).forEach { segment ->
                if (segment.reasoning) reasoning.append(segment.text) else answer.append(segment.text)
            }
        }
        splitter.finish().forEach { segment ->
            if (segment.reasoning) reasoning.append(segment.text) else answer.append(segment.text)
        }
        return answer.toString() to reasoning.toString()
    }

    @Test
    fun `plain answer is passed through untouched`() {
        val (answer, reasoning) = split("Привет", ", как дела?")
        assertEquals("Привет, как дела?", answer)
        assertEquals("", reasoning)
    }

    @Test
    fun `think block is moved out of the answer`() {
        val (answer, reasoning) = split("<think>считаю</think>Ответ 42")
        assertEquals("Ответ 42", answer)
        assertEquals("считаю", reasoning)
    }

    @Test
    fun `reasoning tag is recognised too`() {
        val (answer, reasoning) = split("<reasoning>размышляю</reasoning>Итог")
        assertEquals("Итог", answer)
        assertEquals("размышляю", reasoning)
    }

    @Test
    fun `text before the opening tag stays in the answer`() {
        val (answer, reasoning) = split("Сначала текст<think>мысль</think>конец")
        assertEquals("Сначала текстконец", answer)
        assertEquals("мысль", reasoning)
    }

    @Test
    fun `tag split across chunks is still recognised`() {
        val (answer, reasoning) = split("начало<thi", "nk>мысль</thi", "nk>ответ")
        assertEquals("началоответ", answer)
        assertEquals("мысль", reasoning)
    }

    @Test
    fun `unclosed tag keeps the rest as reasoning`() {
        val (answer, reasoning) = split("до<think>не закончил размышление")
        assertEquals("до", answer)
        assertTrue(reasoning.startsWith("не закончил"))
    }

    @Test
    fun `lone angle brackets are not held back or lost`() {
        val (answer, reasoning) = split("a < b и c > d")
        assertEquals("a < b и c > d", answer)
        assertEquals("", reasoning)
    }

    @Test
    fun `empty chunks are ignored`() {
        val splitter = ReasoningSplitter()
        assertTrue(splitter.accept("").isEmpty())
        assertTrue(splitter.finish().isEmpty())
        assertFalse(splitter.sawReasoningTags())
    }

    @Test
    fun `tag detection is reported`() {
        val splitter = ReasoningSplitter()
        splitter.accept("<think>x</think>")
        assertTrue(splitter.sawReasoningTags())
    }

    @Test
    fun `a repeated opening tag flips the state twice and nothing is lost`() {
        // Models sometimes emit a second opening tag instead of closing; the state machine must
        // stay deterministic rather than swallowing the rest of the answer.
        val (answer, reasoning) = split("<think>раз<think>два</think>")
        assertEquals("раз<" + "think>два", reasoning)
        assertEquals(answer, "")
    }
}

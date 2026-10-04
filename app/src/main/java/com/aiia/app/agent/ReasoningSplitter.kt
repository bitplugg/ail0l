package com.aiia.app.agent

/**
 * Splits a streamed answer into reasoning and reply.
 *
 * Reasoning models emit their chain of thought inline, wrapped in `<think>` or `<reasoning>` tags
 * (DeepSeek style, Qwen3 style). llama.cpp hands us one raw text stream, so the split happens
 * here. A tag can straddle two chunks, so the tail that could still become an opening or closing
 * tag is held back until the next chunk arrives.
 */
class ReasoningSplitter {

    data class Segment(val text: String, val reasoning: Boolean)

    private val openTags = listOf("<" + "think>", "<" + "reasoning>")
    private val closeTags = listOf("</" + "think>", "</" + "reasoning>")

    private val pending = StringBuilder()
    private var insideReasoning = false
    private var sawTag = false

    /** Longest tag that could still be completed by the next chunk. */
    private val longestTag = (openTags + closeTags).maxOf { it.length }

    /** True once any reasoning tag was seen, so plain models are left untouched. */
    fun sawReasoningTags(): Boolean = sawTag

    fun accept(chunk: String): List<Segment> {
        if (chunk.isEmpty()) return emptyList()
        pending.append(chunk)
        val segments = ArrayList<Segment>()

        while (true) {
            val tag = findTag()
            if (tag == null) break

            val leading = pending.substring(0, tag.first)
            if (leading.isNotEmpty()) segments += Segment(leading, insideReasoning)

            val consumed = tag.second
            pending.delete(0, consumed)
            insideReasoning = !insideReasoning
            sawTag = true
            if (pending.isEmpty()) pending.setLength(0)
        }

        // Keep back only what could still turn into a tag; everything else is safe to emit.
        val safe = safeLength()
        if (safe > 0) {
            val text = pending.substring(0, safe)
            pending.delete(0, safe)
            segments += Segment(text, insideReasoning)
        }
        return segments
    }

    /** Flushes whatever is left when the stream ends, so no tail text is silently dropped. */
    fun finish(): List<Segment> {
        val rest = pending.toString()
        val wasInside = insideReasoning
        pending.setLength(0)
        insideReasoning = false
        return if (rest.isEmpty()) emptyList() else listOf(Segment(rest, wasInside))
    }

    /**
     * Only the tag matching the current state switches it: a stray closing tag in plain text and a
     * repeated opening tag inside reasoning stay literal, so nothing leaks into the answer.
     */
    private fun findTag(): Pair<Int, Int>? {
        val text = pending.toString()
        val wanted = if (insideReasoning) closeTags else openTags
        var best: Pair<Int, Int>? = null
        wanted.forEach { tag ->
            val index = text.indexOf(tag)
            if (index >= 0 && (best == null || index < best!!.first)) best = index to (index + tag.length)
        }
        return best
    }

    /**
     * Number of leading characters that cannot be part of a split tag. A complete tag is never
     * held back here because [findTag] already consumed it.
     */
    private fun safeLength(): Int {
        val text = pending.toString()
        if (text.isEmpty()) return 0
        val keep = minOf(longestTag - 1, text.length)
        for (start in text.length - keep until text.length) {
            val tail = text.substring(start)
            val partial = (openTags + closeTags).any { it.startsWith(tail) }
            if (partial) return start
        }
        return text.length
    }
}

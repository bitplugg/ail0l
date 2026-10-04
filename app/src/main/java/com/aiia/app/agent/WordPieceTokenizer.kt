package com.aiia.app.agent

/**
 * BERT style WordPiece tokenizer.
 *
 * The previous implementation mapped one token per UTF-8 byte, which produces meaningless ids for
 * any real exported model. This is the standard "basic tokenizer + WordPiece" pipeline that
 * `bert-base-multilingual-*` vocabularies expect, kept free of Android APIs so it can be unit
 * tested against the published vocabulary behaviour.
 */
class WordPieceTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
    private val lowercase: Boolean,
    private val maxInputChars: Int
) {

    val size: Int get() = vocabulary.size

    val unknownToken: Int = vocabulary[UNK] ?: 0
    val classToken: Int = vocabulary[CLS] ?: -1
    val sepToken: Int = vocabulary[SEP] ?: -1
    val padToken: Int = vocabulary[PAD] ?: 0

    data class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    ) {
        val length: Int get() = inputIds.size

        override fun equals(other: Any?): Boolean = other is Encoding &&
            inputIds.contentEquals(other.inputIds) &&
            attentionMask.contentEquals(other.attentionMask) &&
            tokenTypeIds.contentEquals(other.tokenTypeIds)

        override fun hashCode(): Int = (inputIds.contentHashCode() * 31 + attentionMask.contentHashCode()) * 31 +
            tokenTypeIds.contentHashCode()
    }

    fun encode(text: String, maxTokens: Int = DEFAULT_MAX_TOKENS): Encoding {
        val pieces = basicTokens(text.take(maxInputChars)).flatMap { wordPiece(it) }
        val budget = (maxTokens - SPECIAL_TOKENS).coerceAtLeast(1)
        val trimmed = pieces.take(budget)
        val ids = ArrayList<Long>(trimmed.size + SPECIAL_TOKENS)
        val types = ArrayList<Long>(trimmed.size + SPECIAL_TOKENS)

        if (classToken >= 0) {
            ids += classToken.toLong()
            types += 0L
        }
        trimmed.forEach {
            ids += it.toLong()
            types += 0L
        }
        if (sepToken >= 0) {
            ids += sepToken.toLong()
            types += 0L
        }
        // A single sequence is passed per forward pass, so there is nothing to pad: padding tokens
        // with a mask of 1 would be averaged into the sentence vector.
        val mask = LongArray(ids.size) { 1L }
        return Encoding(ids.toLongArray(), mask, types.toLongArray())
    }

    /** Lowercase, drop control and combining marks, split on whitespace, punctuation and CJK. */
    private fun basicTokens(text: String): List<String> {
        val normalized = if (lowercase) text.lowercase() else text
        // Whitespace becomes a separator instead of disappearing: dropping it would glue words
        // together and every multi-word sentence would land in [UNK].
        return normalized
            .map { if (isWhitespace(it) || isControl(it)) ' ' else it }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .flatMap { splitWord(it) }
    }

    /**
     * Splits one whitespace-delimited chunk into vocabulary-sized tokens. Punctuation and CJK
     * ideographs become standalone pieces: gluing them to the word makes "word!" fall back to
     * [UNK] because BERT vocabularies only carry them separately.
     */
    private fun splitWord(token: String): List<String> {
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        for (ch in token) {
            val standalone = isCombining(ch.code) || isPunctuation(ch.code) || isCjk(ch.code)
            if (standalone) {
                if (current.isNotEmpty()) {
                    pieces += current.toString()
                    current.setLength(0)
                }
                if (!isCombining(ch.code)) pieces += ch.toString()
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    /** Greedy longest-match-first WordPiece over the vocabulary. */
    private fun wordPiece(token: String): List<Int> {
        if (token.isEmpty()) return emptyList()
        vocabulary[token]?.let { return listOf(it) }
        if (token.length > MAX_WORD_CHARS) return listOf(unknownToken)

        val ids = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var match: Int? = null
            while (start < end) {
                val piece = if (start == 0) token.substring(0, end) else "##${token.substring(start, end)}"
                val id = vocabulary[piece]
                if (id != null) {
                    match = id
                    break
                }
                end--
            }
            if (match == null) return listOf(unknownToken)
            ids += match
            start = end
        }
        return ids
    }

    companion object {
        const val DEFAULT_MAX_TOKENS = 256
        private const val SPECIAL_TOKENS = 2
        private const val MAX_WORD_CHARS = 100
        private const val MAX_INPUT_CHARS = 20_000
        private const val UNK = "[UNK]"
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val PAD = "[PAD]"

        /**
         * Reads a `vocab.txt` style file: one token per line, index equals line number.
         * Returns null when the file does not look like a vocabulary.
         */
        fun fromVocabText(text: String, lowercase: Boolean = true): WordPieceTokenizer? {
            if (text.isBlank()) return null
            val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
            if (lines.size < 100) return null
            val vocabulary = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { index, token -> vocabulary[token] = index }
            if (!vocabulary.containsKey(UNK)) return null
            return WordPieceTokenizer(vocabulary, lowercase, MAX_INPUT_CHARS)
        }

        private fun isWhitespace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch.code == 0x00A0

        private fun isControl(ch: Char): Boolean = ch.code in 0x00..0x1F || ch.code in 0x7F..0x9F

        private fun isCombining(cp: Int): Boolean = cp in 0x0300..0x036F || cp in 0xFE20..0xFE2F

        private fun isPunctuation(cp: Int): Boolean = cp in 0x21..0x2F || cp in 0x3A..0x40 || cp in 0x5B..0x60 ||
            cp in 0x7B..0x7E || cp in 0x2010..0x2027 || cp in 0x2030..0x205E ||
            cp in 0x3001..0x3003

        private fun isCjk(cp: Int): Boolean = cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x20000..0x2A6DF ||
            cp in 0x3040..0x30FF ||
            cp in 0xAC00..0xD7AF
    }
}

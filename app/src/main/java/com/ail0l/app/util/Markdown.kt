package com.ail0l.app.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Минимальный markdown-рендер для сообщений:
 * **жирный**, *курсив*, `код`, заголовки #/##/###, списки - и 1.,
 * ссылки [текст](url). Строит AnnotatedString построчно.
 */
object Markdown {

    fun render(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
        text.split('\n').forEachIndexed { idx, line ->
            if (idx > 0) append('\n')
            val t = line.trim()
            when {
                t.startsWith("### ") -> appendStyled(t.substring(4), accent)
                t.startsWith("## ") -> appendStyled(t.substring(3), accent)
                t.startsWith("# ") -> appendStyled(t.substring(2), accent)
                t.startsWith("- ") || t.startsWith("• ") -> {
                    append("• ")
                    appendStyled(t.substring(2), accent)
                }
                Regex("""^\d+[.)]\s+""").containsMatchIn(t) -> appendStyled(t, accent)
                else -> appendStyled(t, accent)
            }
        }
    }

    private fun AnnotatedString.Builder.appendStyled(
        text: String,
        accent: Color
    ) {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append('*'); i++
                    }
                }

                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('`'); i++
                    }
                }

                text.startsWith("*", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('*'); i++
                    }
                }

                text.startsWith("[", i) -> {
                    val close = text.indexOf(']', i)
                    val open = text.indexOf('(', close)
                    val end = if (close > 0 && open == close + 1) {
                        val paren = text.indexOf(')', open)
                        if (paren > 0) paren + 1 else -1
                    } else -1
                    if (end > 0) {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                textDecoration = TextDecoration.Underline
                            )
                        ) { append(text.substring(i + 1, close)) }
                        i = end
                    } else {
                        append('['); i++
                    }
                }

                else -> {
                    val nextIdx = listOfIndexed(text, i)
                    append(text.substring(i, nextIdx))
                    i = nextIdx
                }
            }
        }
    }

    /** индекс ближайшего следующего спецсимвола **, ` или * начиная с [from] */
    private fun listOfIndexed(text: String, from: Int): Int {
        var best = text.length
        for (tok in listOf("**", "`", "*")) {
            val idx = text.indexOf(tok, from)
            if (idx in 0 until best) best = idx
        }
        return best
    }

    fun plain(text: String): String = text.replace("**", "").replace("*", "").replace("`", "")
}
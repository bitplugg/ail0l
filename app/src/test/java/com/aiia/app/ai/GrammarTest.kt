package com.aiia.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarTest {

    private val grammar = Grammar.forToolCalls()

    private val rules: Map<String, String> = grammar.lines()
        .map { it.trim() }
        .filter { it.contains("::=") }
        .associate { line ->
            val name = line.substringBefore("::=").trim()
            name to line.substringAfter("::=").trim()
        }

    @Test
    fun `rule names only use characters the llama parser accepts`() {
        // llama.cpp parses names as [a-zA-Z0-9-]: an underscore silently splits the name.
        val invalid = rules.keys.filter { name -> name.any { !(it.isLetterOrDigit() || it == '-') } }
        assertTrue("invalid rule names: $invalid", invalid.isEmpty())
    }

    @Test
    fun `every referenced rule is defined`() {
        val referenced = rules.values
            .flatMap { body -> body.split("|", " ", "(", ")", "?", "*", "[", "]").filter { it.isNotBlank() } }
            .map { it.trim() }
            .filter { token ->
                // A bare reference is one lowercase identifier; ranges like [eE] are quoted-ish
                // groups that were already split away by the delimiters above.
                token.first().isLowerCase() && token.all { it.isLetterOrDigit() || it == '-' }
            }
            .toSet()
        // "eE" is the exponent literal in the number rule, not a rule reference.
        val missing = referenced - rules.keys - setOf("eE")
        assertTrue("undefined rules: $missing", missing.isEmpty())
    }

    @Test
    fun `root exists and is the only entry point llama is asked for`() {
        assertTrue(rules.containsKey("root"))
        assertTrue(Grammar.isValid(grammar))
        assertFalse(Grammar.isValid("root = text"))
    }

    @Test
    fun `call tags are emitted without a zero width space`() {
        // A zero width character in the literal makes the block unreachable: no model emits it.
        assertEquals(-1, grammar.indexOf('\u200B'))
        assertTrue(grammar.contains("<" + "tool_call>"))
        assertTrue(grammar.contains("<mcp_call>"))
    }

    @Test
    fun `prose stays unconstrained and only one call block is allowed`() {
        val root = rules.getValue("root")
        assertTrue(root.contains("text"))
        assertTrue(root.contains("block?"))
    }

    @Test
    fun `json value alternatives cover the types the parser accepts`() {
        val value = rules.getValue("value")
        listOf("object", "array", "string", "number", "true", "false", "null").forEach {
            assertTrue("value is missing $it", value.contains(it))
        }
    }
}

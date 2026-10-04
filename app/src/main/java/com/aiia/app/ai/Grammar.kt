package com.aiia.app.ai

/**
 * GBNF applied when a turn may end with a tool call.
 *
 * The model keeps full freedom over the prose part and may append at most one call block, so a
 * stray brace inside the explanation cannot truncate the answer the way a JSON-only grammar
 * would. Rule names avoid underscores: the llama.cpp parser only accepts `[a-zA-Z0-9-]`.
 */
object Grammar {

    /** Kept out of the literals to stay consistent with how the call tags are parsed. */
    private fun tag(name: String): String = "<" + name + ">"

    private val TOOL_OPEN = tag("tool_call")
    private val TOOL_CLOSE = tag("/tool_call")
    private val MCP_OPEN = tag("mcp_call")
    private val MCP_CLOSE = tag("/mcp_call")

    private val BODY = """
        root  ::= text block?
        text  ::= [^<]*
        block ::= toolblock | mcpblock
        toolblock ::= "$TOOL_OPEN" object "$TOOL_CLOSE"
        mcpblock  ::= "$MCP_OPEN" object "$MCP_CLOSE"
        object ::= "{" ws (pair (ws "," ws pair)*)? ws "}"
        pair  ::= string ws ":" ws value
        value ::= object | array | string | number | "true" | "false" | "null"
        array ::= "[" ws (value (ws "," ws value)*)? ws "]"
        string ::= "\"" char* "\""
        char  ::= [^"\\] | "\\" (["\\/bfnrt] | "u" hex hex hex hex)
        hex   ::= [0-9a-fA-F]
        number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
        ws    ::= [ \t\n\r]*
    """

    fun forToolCalls(): String = BODY.trimIndent()

    fun isValid(grammar: String): Boolean = grammar.lineSequence().any { it.contains("::=") }
}

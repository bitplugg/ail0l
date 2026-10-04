package com.aiia.app.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {
    private val parser = ToolCallParser()

    private fun toolCall(json: String) = "<tool_call>$json</tool_call>"

    private fun mcpCall(json: String) = "<mcp_call>$json</mcp_call>"

    @Test
    fun `parses a tool_call tag`() {
        val call = parser.parse("Сейчас проверю.\n" + toolCall("""{"tool":"get_battery"}"""))
        assertNotNull(call)
        assertEquals("get_battery", call!!.name)
        assertFalse(call.isMcp)
    }

    @Test
    fun `parses arguments of a tool_call`() {
        val call = parser.parse(toolCall("""{"tool":"exec_shell","arguments":{"command":"ls -la"}}"""))
        assertEquals("exec_shell", call!!.name)
        assertEquals("ls -la", call.command)
    }

    @Test
    fun `accepts name as an alias for tool`() {
        val call = parser.parse(toolCall("""{"name":"open_app","arguments":{"package":"com.x"}}"""))
        assertEquals("open_app", call!!.name)
        assertEquals("com.x", call.packageName)
    }

    @Test
    fun `parses an mcp_call tag`() {
        val call = parser.parse(mcpCall("""{"server":"files","tool":"read","arguments":{"path":"/tmp/a"}}"""))
        assertNotNull(call)
        assertTrue(call!!.isMcp)
        assertEquals("files", call.mcpServer)
        assertEquals("read", call.mcpTool)
    }

    @Test
    fun `mcp_call wins over tool_call when both are present`() {
        val call =
            parser.parse(
                toolCall("""{"tool":"get_battery"}""") + mcpCall("""{"server":"files","tool":"read"}""")
            )
        assertTrue(call!!.isMcp)
        assertEquals("files", call.mcpServer)
    }

    @Test
    fun `parses a fenced json block`() {
        val call = parser.parse("```json\n{\"tool\":\"get_battery\"}\n```")
        assertEquals("get_battery", call!!.name)
    }

    @Test
    fun `parses a bare json object`() {
        val call = parser.parse("""{"tool":"get_battery"}""")
        assertEquals("get_battery", call!!.name)
    }

    @Test
    fun `returns null for prose`() {
        assertNull(parser.parse("Просто текст без вызова инструмента."))
    }

    @Test
    fun `returns null for broken json`() {
        assertNull(parser.parse(toolCall("{not json}")))
        assertNull(parser.parse(toolCall("""{"tool":}""")))
    }

    @Test
    fun `returns null when neither tool nor name is present`() {
        assertNull(parser.parse(toolCall("""{"arguments":{"command":"ls"}}""")))
    }

    @Test
    fun `ignores unknown argument types gracefully`() {
        val call = parser.parse(toolCall("""{"tool":"x","arguments":{"nested":{"a":1}},"extra":7}"""))
        assertNotNull(call)
        assertEquals("x", call!!.name)
    }

    @Test
    fun `keeps primitive arguments when a sibling argument is an object`() {
        val call =
            parser.parse(
                toolCall("""{"tool":"shell","arguments":{"nested":{"a":1},"command":"ls"}}""")
            )
        assertNotNull(call)
        assertEquals("ls", call!!.command)
    }

    @Test
    fun `null valued arguments are dropped`() {
        val call = parser.parse(toolCall("""{"tool":"x","arguments":{"a":null,"b":"kept"}}"""))
        assertNotNull(call)
        assertEquals(mapOf("b" to "kept"), call!!.arguments)
    }
}

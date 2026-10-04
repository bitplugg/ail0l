package com.aiia.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val sample =
        BackupDocument(
            exportedAt = 1_700_000_000_000L,
            conversations = listOf(
                BackupConversation(
                    title = "Поездка",
                    pinned = true,
                    createdAt = 1_699_000_000_000L,
                    messages = listOf(
                        BackupMessage("user", "Куда летим?", 1_699_000_001_000L),
                        BackupMessage("assistant", "В Барселону", 1_699_000_002_000L)
                    )
                )
            ),
            facts = listOf(BackupFact("Любит кино", "user", true, 1_699_000_003_000L))
        )

    @Test
    fun `round trip keeps conversations messages and facts`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sample))
        assertNotNull(decoded)
        assertNotNull(decoded!!)
        assertEquals(BackupDocument.FORMAT, decoded.format)
        assertEquals(BackupDocument.VERSION, decoded.version)
        assertEquals(sample.exportedAt, decoded!!.exportedAt)
        assertEquals(1, decoded.conversations.size)
        assertEquals("Поездка", decoded.conversations[0].title)
        assertTrue(decoded.conversations[0].pinned)
        assertEquals(2, decoded.conversations[0].messages.size)
        assertEquals("Куда летим?", decoded.conversations[0].messages[0].content)
        assertEquals("assistant", decoded.conversations[0].messages[1].role)
        assertEquals(listOf("Любит кино"), decoded.facts.map { it.fact })
        assertEquals(2, decoded.messageCount)
    }

    @Test
    fun `empty document is recognised and reported as empty`() {
        val empty = BackupDocument()
        val decoded = BackupCodec.decode(BackupCodec.encode(empty))!!
        assertTrue(BackupCodec.isEmpty(decoded))
        assertEquals(0, decoded.messageCount)
    }

    @Test
    fun `unknown fields from a newer build are ignored`() {
        val text = """
            {
              "format": "aiia-backup",
              "version": 1,
              "somethingNew": {"nested": true},
              "conversations": [{"title":"t","messages":[{"role":"user","content":"hi"}]}]
            }
        """.trimIndent()
        val decoded = BackupCodec.decode(text)
        assertNotNull(decoded)
        assertEquals("hi", decoded!!.conversations[0].messages[0].content)
    }

    @Test
    fun `garbage and foreign json are rejected`() {
        assertNull(BackupCodec.decode("не json вовсе"))
        assertNull(BackupCodec.decode(""))
        assertNull(BackupCodec.decode("[1,2,3]"))
        assertNull(BackupCodec.decode("""{"format":"other-app","version":1}"""))
        assertFalse(BackupCodec.looksLikeBackup("{ broken"))
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val decoded = BackupCodec.decode("""{"format":"aiia-backup"}""")!!
        assertEquals(BackupDocument.VERSION, decoded.version)
        assertTrue(decoded.conversations.isEmpty())
        assertTrue(BackupCodec.isEmpty(decoded))
    }
}

class LegacyTranscriptTest {

    @Test
    fun `parses russian transcript`() {
        val text = """
            # Отпуск
            [Вы]:
            Куда летим?
            [AIIA]:
            В Барселону
            Первым рейсом.
        """.trimIndent()
        val messages = LegacyTranscript.parse(text)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Куда летим?", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("В Барселону\nПервым рейсом.", messages[1].content)
    }

    @Test
    fun `reads title from the heading`() {
        assertEquals("Отпуск", LegacyTranscript.title("# Отпуск\n[Вы]:\nпривет"))
        assertEquals("Импортированный диалог", LegacyTranscript.title("привет"))
    }

    @Test
    fun `blank and speakerless input yields nothing`() {
        assertTrue(LegacyTranscript.parse("").isEmpty())
        assertTrue(LegacyTranscript.parse("   \n  ").isEmpty())
        assertTrue(LegacyTranscript.parse("просто текст без меток").isEmpty())
    }

    @Test
    fun `carriage returns are tolerated`() {
        val messages = LegacyTranscript.parse("# T\r\n[Вы]:\r\nпривет\r\n[AIIA]:\r\nответ\r\n")
        assertEquals(2, messages.size)
        assertEquals("привет", messages[0].content)
    }
}

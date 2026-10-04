package com.aiia.app.util

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderParserTest {
    private val now: Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 10, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `parses a plain timer`() {
        val request = ReminderParser.parse("таймер 5 минут", now)
        assertNotNull(request)
        assertTrue(request!!.isTimer)
        assertEquals(now + 5 * 60_000L, request.triggerAt)
    }

    @Test
    fun `parses a timer with seconds`() {
        val request = ReminderParser.parse("таймер на 90 секунд", now)
        assertNotNull(request)
        assertTrue(request!!.isTimer)
        assertEquals(now + 90_000L, request.triggerAt)
    }

    @Test
    fun `timer label falls back to the duration`() {
        val request = ReminderParser.parse("таймер 5 минут", now)
        assertEquals("Таймер на 5 минут", request!!.text)
    }

    @Test
    fun `parses a relative reminder and strips the duration`() {
        val request = ReminderParser.parse("напомни через 10 минут выпить воду", now)
        assertNotNull(request)
        assertFalse(request!!.isTimer)
        assertEquals(now + 10 * 60_000L, request.triggerAt)
        assertEquals("выпить воду", request.text)
    }

    @Test
    fun `parses a relative reminder written with capital letters`() {
        val request = ReminderParser.parse("Напомни через 2 часа выйти из дома", now)
        assertNotNull(request)
        assertEquals(now + 2 * 3_600_000L, request!!.triggerAt)
        assertEquals("выйти из дома", request.text)
    }

    @Test
    fun `parses a clock time reminder later today`() {
        val request = ReminderParser.parse("напомни в 18:30 позвонить маме", now)
        assertNotNull(request)
        assertFalse(request!!.isTimer)
        assertEquals("позвонить маме", request.text)

        val scheduled = Calendar.getInstance().apply { timeInMillis = request.triggerAt }
        assertEquals(18, scheduled.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, scheduled.get(Calendar.MINUTE))
        assertTrue(request.triggerAt > now)
    }

    @Test
    fun `clock time in the past rolls over to tomorrow`() {
        val request = ReminderParser.parse("напомни в 07:00 встать", now)
        assertNotNull(request)
        assertTrue(request!!.triggerAt > now)
        assertEquals(
            1,
            Calendar.getInstance().apply { timeInMillis = request.triggerAt }
                .get(Calendar.DAY_OF_YEAR) -
                Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        )
    }

    @Test
    fun `explicit tomorrow is honoured`() {
        val request = ReminderParser.parse("напомни завтра в 09:15 совещание", now)
        assertNotNull(request)
        val scheduled = Calendar.getInstance().apply { timeInMillis = request!!.triggerAt }
        assertEquals(9, scheduled.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, scheduled.get(Calendar.MINUTE))
        assertEquals(
            1,
            scheduled.get(Calendar.DAY_OF_YEAR) -
                Calendar.getInstance()
                    .apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        )
    }

    @Test
    fun `accepts a dot between hours and minutes`() {
        val request = ReminderParser.parse("напомни в 21.05 полить цветы", now)
        assertNotNull(request)
        val scheduled = Calendar.getInstance().apply { timeInMillis = request!!.triggerAt }
        assertEquals(21, scheduled.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, scheduled.get(Calendar.MINUTE))
    }

    @Test
    fun `falls back to a generic label when the body is empty`() {
        val request = ReminderParser.parse("напомни в 18:30", now)
        assertNotNull(request)
        assertEquals("Напоминание на 18:30", request!!.text)
    }

    @Test
    fun `ignores unrelated text`() {
        assertNull(ReminderParser.parse("сколько сейчас времени?", now))
        assertNull(ReminderParser.parse("", now))
        assertNull(ReminderParser.parse("   ", now))
    }

    @Test
    fun `rejects an impossible clock time`() {
        assertNull(ReminderParser.parse("напомни в 25:00 что-то", now))
        assertNull(ReminderParser.parse("напомни в 10:70 что-то", now))
    }

    @Test
    fun `timer without a duration is not scheduled`() {
        assertNull(ReminderParser.parse("таймер поставь", now))
    }

    @Test
    fun `combines several duration units`() {
        val request = ReminderParser.parse("таймер 1 час 30 минут", now)
        assertNotNull(request)
        assertEquals(now + (3_600_000L + 1_800_000L), request!!.triggerAt)
    }

    @Test
    fun `strips the whole plural unit from the label`() {
        val request = ReminderParser.parse("напомни через 2 часа пить воду", now)
        assertNotNull(request)
        assertEquals(now + 2 * 3_600_000L, request!!.triggerAt)
        assertEquals("пить воду", request.text)
    }

    @Test
    fun `handles several plural duration forms`() {
        assertEquals("Таймер на 90 секунд", ReminderParser.parse("таймер на 90 секунд", now)!!.text)
        assertEquals(90_000L, ReminderParser.parse("таймер на 90 секунд", now)!!.triggerAt - now)
        assertEquals(3 * 86_400_000L, ReminderParser.parse("таймер 3 дня", now)!!.triggerAt - now)
        assertEquals(5 * 60_000L, ReminderParser.parse("таймер 5 минут", now)!!.triggerAt - now)
    }

    @Test
    fun `timer with duration but no verb words is rejected`() {
        assertNull(ReminderParser.parse("через 5 минут", now))
    }
}

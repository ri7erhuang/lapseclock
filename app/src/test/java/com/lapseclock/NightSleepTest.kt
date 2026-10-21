package com.lapseclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NightSleepTest {

    @Test
    fun `sleep hour covers 22 to 08`() {
        assertFalse(isSleepHour(21))
        assertTrue(isSleepHour(22))
        assertTrue(isSleepHour(23))
        assertTrue(isSleepHour(0))
        assertTrue(isSleepHour(7))
        assertFalse(isSleepHour(8))
        assertFalse(isSleepHour(12))
    }

    @Test
    fun `night key belongs to the evening that started it`() {
        assertEquals("2026-10-20", nightKeyOf(LocalDateTime.of(2026, 10, 20, 22, 0)))
        assertEquals("2026-10-20", nightKeyOf(LocalDateTime.of(2026, 10, 20, 23, 59)))
        assertEquals("2026-10-20", nightKeyOf(LocalDateTime.of(2026, 10, 21, 0, 30)))
        assertEquals("2026-10-20", nightKeyOf(LocalDateTime.of(2026, 10, 21, 7, 59)))
    }

    @Test
    fun `manual sleep always wins`() {
        assertTrue(isSleeping(manualSleep = true, snoozeNight = "", now = LocalDateTime.of(2026, 10, 21, 12, 0)))
    }

    @Test
    fun `snooze only silences its own night`() {
        val earlyMorning = LocalDateTime.of(2026, 10, 21, 1, 0)
        assertFalse(isSleeping(manualSleep = false, snoozeNight = "2026-10-20", now = earlyMorning))
        assertTrue(isSleeping(manualSleep = false, snoozeNight = "2026-10-19", now = earlyMorning))

        val nextNight = LocalDateTime.of(2026, 10, 21, 22, 30)
        assertTrue(isSleeping(manualSleep = false, snoozeNight = "2026-10-20", now = nextNight))
    }

    @Test
    fun `daytime is never sleeping`() {
        assertFalse(isSleeping(manualSleep = false, snoozeNight = "", now = LocalDateTime.of(2026, 10, 21, 9, 0)))
        assertFalse(isSleeping(manualSleep = false, snoozeNight = "2026-10-20", now = LocalDateTime.of(2026, 10, 21, 21, 59)))
    }
}

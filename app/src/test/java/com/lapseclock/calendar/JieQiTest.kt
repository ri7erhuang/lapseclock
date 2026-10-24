package com.lapseclock.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class JieQiTest {

    @Test
    fun `a year has all 24 jieqi`() {
        assertEquals(24, jieQiInYear(2026).size)
        assertEquals("小寒", jieQiInYear(2026)[LocalDate.of(2026, 1, 5)])
        assertEquals("冬至", jieQiInYear(2026)[LocalDate.of(2026, 12, 22)])
    }

    @Test
    fun `next jieqi counts today as a hit`() {
        val expected = LocalDate.of(2026, 10, 23) to "霜降"
        assertEquals(expected, nextJieQi(LocalDate.of(2026, 10, 22)))
        assertEquals(expected, nextJieQi(LocalDate.of(2026, 10, 23)))
    }

    @Test
    fun `next jieqi crosses into the next year`() {
        assertEquals(LocalDate.of(2027, 1, 5) to "小寒", nextJieQi(LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `the table is built once per year`() {
        assertSame(jieQiInYear(2026), jieQiInYear(2026))
    }
}

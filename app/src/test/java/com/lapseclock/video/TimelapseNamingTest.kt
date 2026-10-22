package com.lapseclock.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TimelapseNamingTest {

    @Test
    fun `single day name`() {
        assertEquals(
            "timelapse_2026-10-20_30fps.mp4",
            timelapseOutputName(listOf(LocalDate.of(2026, 10, 20)), 30),
        )
    }

    @Test
    fun `consecutive days name`() {
        val days = (1..20).map { LocalDate.of(2026, 10, it) }
        assertEquals(
            "timelapse_2026-10-01_2026-10-20_24fps.mp4",
            timelapseOutputName(days, 24),
        )
    }

    @Test
    fun `skipped days are marked with day count`() {
        assertEquals(
            "timelapse_2026-10-01_2026-10-20_2days_30fps.mp4",
            timelapseOutputName(
                listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20)),
                30,
            ),
        )
    }

    @Test
    fun `order does not matter`() {
        assertEquals(
            "timelapse_2026-10-01_2026-10-03_30fps.mp4",
            timelapseOutputName(
                listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
                30,
            ),
        )
    }
}

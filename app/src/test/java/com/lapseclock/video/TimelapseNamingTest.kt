package com.lapseclock.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TimelapseNamingTest {

    @Test
    fun `single day name`() {
        assertEquals(
            "timelapse_2026-10-20_30fps.mp4",
            timelapseOutputName(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), 30),
        )
    }

    @Test
    fun `range name`() {
        assertEquals(
            "timelapse_2026-10-01_2026-10-20_24fps.mp4",
            timelapseOutputName(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), 24),
        )
    }
}

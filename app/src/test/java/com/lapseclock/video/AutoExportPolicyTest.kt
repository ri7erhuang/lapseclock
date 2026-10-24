package com.lapseclock.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AutoExportPolicyTest {

    private val yesterday = LocalDate.of(2026, 10, 20)

    private fun shouldRun(
        enabled: Boolean = true,
        sleeping: Boolean = false,
        exporting: Boolean = false,
        lastAutoExportDate: String = "",
        yesterdayCount: Int = 120,
        nowMillis: Long = 1_000_000L,
        retryAfterMillis: Long = 0L,
    ) = AutoExportPolicy.shouldRun(
        enabled = enabled,
        sleeping = sleeping,
        exporting = exporting,
        lastAutoExportDate = lastAutoExportDate,
        yesterday = yesterday,
        yesterdayCount = yesterdayCount,
        nowMillis = nowMillis,
        retryAfterMillis = retryAfterMillis,
    )

    @Test
    fun `runs after sleep when yesterday has photos`() {
        assertTrue(shouldRun())
    }

    @Test
    fun `does not run when disabled or sleeping`() {
        assertFalse(shouldRun(enabled = false))
        assertFalse(shouldRun(sleeping = true))
    }

    @Test
    fun `does not run twice for the same day`() {
        assertFalse(shouldRun(lastAutoExportDate = "2026-10-20"))
        assertTrue(shouldRun(lastAutoExportDate = "2026-10-19"))
    }

    @Test
    fun `does not run while exporting or without photos`() {
        assertFalse(shouldRun(exporting = true))
        assertFalse(shouldRun(yesterdayCount = 0))
    }

    @Test
    fun `respects failure backoff`() {
        assertFalse(shouldRun(nowMillis = 999_999L, retryAfterMillis = 1_000_000L))
        assertTrue(shouldRun(nowMillis = 1_000_000L, retryAfterMillis = 1_000_000L))
    }
}

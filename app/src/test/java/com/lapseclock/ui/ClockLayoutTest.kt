package com.lapseclock.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockLayoutTest {

    /** 手机横屏：393dp 高，时钟块（大字 + 日期）约 276dp 高 */
    private val phoneH = 393.dp
    private val blockH = 276.dp

    @Test
    fun `travel keeps content out of the reserve`() {
        val reserve = 46.dp
        val travel = freeTravel(phoneH, blockH, reserve)
        val lowestEdge = (phoneH + blockH) / 2 + travel
        assertTrue(phoneH - lowestEdge >= reserve)
    }

    @Test
    fun `reserve eats the travel instead of shrinking it silently`() {
        // 居中后单侧空白 58.5dp，扣掉 46dp 保留带只剩 12.5dp
        assertEquals(58.5f, freeTravel(phoneH, blockH, 0.dp).value, 0.01f)
        assertEquals(12.5f, freeTravel(phoneH, blockH, 46.dp).value, 0.01f)
    }

    @Test
    fun `no travel when content already fills the band`() {
        assertEquals(0f, freeTravel(phoneH, phoneH, 0.dp).value, 0f)
        assertEquals(0f, freeTravel(phoneH, 380.dp, 46.dp).value, 0f)
    }

    @Test
    fun `bigger screen allows the full burn offset`() {
        assertTrue(freeTravel(1200.dp, blockH, 46.dp) >= 60.dp)
    }

    @Test
    fun `level only scales the horizontal amplitude`() {
        for (level in 0..2) {
            val maxX = when (level) { 0 -> 30f; 1 -> 60f; else -> 80f }
            repeat(64) { i ->
                val (x, y) = antiBurnOffset(i * 0x9E3779B9.toInt() + level, level)
                assertTrue("x=$x exceeds ±$maxX at level $level", x.value in -maxX..maxX)
                assertTrue("y=$y exceeds ±20 at level $level", y.value in -20f..20f)
            }
        }
    }

    @Test
    fun `the vertical micro-shift ignores the level`() {
        val seed = 0x51ED2701
        val ys = (0..2).map { level -> antiBurnOffset(seed, level).second.value }
        assertEquals(ys[0], ys[1], 0f)
        assertEquals(ys[1], ys[2], 0f)
        assertTrue("y=${ys[0]} outside ±20", ys[0] in -20f..20f)
    }

    @Test
    fun `off level shifts nothing`() {
        val (x, y) = antiBurnOffset(0x9E3779B9.toInt(), -1)
        assertEquals(0f, x.value, 0f)
        assertEquals(0f, y.value, 0f)
    }
}

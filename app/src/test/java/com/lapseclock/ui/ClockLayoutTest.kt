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
}

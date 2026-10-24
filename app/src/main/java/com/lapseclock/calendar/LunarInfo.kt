package com.lapseclock.calendar

import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import java.time.LocalDate

data class DayInfo(
    val lunarText: String,
    val specialText: String?,
)

private val cache = object : LinkedHashMap<LocalDate, DayInfo>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, DayInfo>) = size > 128
}

/** 一年的节气表（日期 → 节气名，24 个）缓存，第二屏的年度格子要用 */
private val jieQiCache = object : LinkedHashMap<Int, Map<LocalDate, String>>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<LocalDate, String>>) = size > 4
}

/** [year] 年全部节气：日期 → 名称。逐日过一遍农历，一年 365 次，结果按年缓存 */
fun jieQiInYear(year: Int): Map<LocalDate, String> = synchronized(jieQiCache) {
    jieQiCache.getOrPut(year) {
        val out = LinkedHashMap<LocalDate, String>(24)
        var d = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        while (!d.isAfter(end)) {
            val jq = Solar.fromYmd(d.year, d.monthValue, d.dayOfMonth).lunar.jieQi
            if (jq.isNotEmpty()) out[d] = jq
            d = d.plusDays(1)
        }
        out
    }
}

/** 从 [from]（含当天）起的下一个节气；先查当年表，跨年时补下一年 */
fun nextJieQi(from: LocalDate): Pair<LocalDate, String>? =
    jieQiInYear(from.year).entries.firstOrNull { !it.key.isBefore(from) }?.let { it.key to it.value }
        ?: jieQiInYear(from.year + 1).entries.firstOrNull()?.let { it.key to it.value }

/** 公历日期 → 农历文本 + 节日/节气标记（最多取 2 个，用 · 连接） */
fun dayInfoOf(date: LocalDate): DayInfo = synchronized(cache) {
    cache.getOrPut(date) {
        val lunar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth).lunar
        val leap = if (lunar.month < 0) "闰" else ""
        val lunarText = "农历$leap${lunar.monthInChinese}月${lunar.dayInChinese}"

        val specials = LinkedHashSet<String>()
        specials.addAll(lunar.festivals)
        specials.addAll(lunar.solar.festivals)
        val jieQi = lunar.jieQi
        if (jieQi.isNotEmpty()) specials.add(jieQi)

        DayInfo(
            lunarText = lunarText,
            specialText = specials.take(2).joinToString("·").ifEmpty { null },
        )
    }
}

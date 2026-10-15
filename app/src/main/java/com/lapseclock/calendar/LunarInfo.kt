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

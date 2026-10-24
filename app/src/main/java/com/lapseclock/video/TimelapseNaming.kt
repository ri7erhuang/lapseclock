package com.lapseclock.video

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 成片文件名带日期，相册里一眼能认出是哪几天的：
 * timelapse_2026-10-20_30fps.mp4                    单日
 * timelapse_2026-10-01_2026-10-20_30fps.mp4         连续多日
 * timelapse_2026-10-01_2026-10-20_3days_30fps.mp4   跳着选的多日（跨度 ≠ 天数，标出来免得误解）
 */
fun timelapseOutputName(dates: Collection<LocalDate>, fps: Int): String {
    val sorted = dates.distinct().sorted()
    if (sorted.isEmpty()) return "timelapse_empty_${fps}fps.mp4"

    val first = sorted.first()
    val last = sorted.last()
    val spanDays = ChronoUnit.DAYS.between(first, last) + 1
    val range = when {
        sorted.size == 1 -> first.toString()
        sorted.size.toLong() == spanDays -> "${first}_$last"
        else -> "${first}_${last}_${sorted.size}days"
    }
    return "timelapse_${range}_${fps}fps.mp4"
}

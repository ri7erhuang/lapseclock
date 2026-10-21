package com.lapseclock.video

import java.time.LocalDate

/**
 * 成片文件名带日期范围，相册里一眼能认出是哪几天：
 * timelapse_2026-10-20_30fps.mp4
 * timelapse_2026-10-01_2026-10-20_30fps.mp4
 */
fun timelapseOutputName(from: LocalDate, to: LocalDate, fps: Int): String {
    val range = if (from == to) from.toString() else "${from}_${to}"
    return "timelapse_${range}_${fps}fps.mp4"
}

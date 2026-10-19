package com.lapseclock

import java.time.LocalDateTime

const val NIGHT_SLEEP_START_HOUR = 22
const val NIGHT_SLEEP_END_HOUR = 8

fun isSleepHour(hour: Int): Boolean =
    hour >= NIGHT_SLEEP_START_HOUR || hour < NIGHT_SLEEP_END_HOUR

/**
 * 这一夜归属哪一天：22:00 之后算当天，次日 08:00 之前算前一天。
 * 用于记录「当晚被手动唤醒」，让次日 22:00 恢复自动休眠。
 */
fun nightKeyOf(now: LocalDateTime): String {
    val day = if (now.hour >= NIGHT_SLEEP_START_HOUR) now.toLocalDate() else now.toLocalDate().minusDays(1)
    return day.toString()
}

/**
 * 统一的休眠判定，拍照循环 / 天气 / 积存金 / 时钟界面都用它，避免各处各算一套。
 * - 手动休眠优先，无条件生效
 * - 夜间时段可被「当晚不再休眠」临时解除，天亮后自然失效
 */
fun isSleeping(
    manualSleep: Boolean,
    snoozeNight: String,
    now: LocalDateTime = LocalDateTime.now(),
): Boolean {
    if (manualSleep) return true
    if (!isSleepHour(now.hour)) return false
    return snoozeNight != nightKeyOf(now)
}

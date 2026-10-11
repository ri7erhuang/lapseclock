package com.lapseclock

const val NIGHT_SLEEP_START_HOUR = 22
const val NIGHT_SLEEP_END_HOUR = 8

fun isSleepHour(hour: Int): Boolean =
    hour >= NIGHT_SLEEP_START_HOUR || hour < NIGHT_SLEEP_END_HOUR

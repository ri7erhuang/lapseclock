package com.lapseclock.video

import java.time.LocalDate

/**
 * 「休眠结束后自动合成昨日」的纯判定逻辑，便于单测；副作用（启动导出、写 DataStore）留在 ViewModel。
 */
object AutoExportPolicy {

    /** 失败后至少等 30 分钟再试，避免每分钟重编码 */
    const val RETRY_MS = 30 * 60_000L

    /** 自动合成前的剩余空间下限：输出先落 cache，再写入相册 */
    const val MIN_FREE_BYTES = 800L * 1024 * 1024

    /** 与手动导出默认值保持一致 */
    const val FPS = 30
    const val WIDTH = 1920
    const val HEIGHT = 1080

    fun shouldRun(
        enabled: Boolean,
        sleeping: Boolean,
        exporting: Boolean,
        lastAutoExportDate: String,
        yesterday: LocalDate,
        yesterdayCount: Int,
        nowMillis: Long,
        retryAfterMillis: Long,
    ): Boolean {
        if (!enabled || sleeping || exporting) return false
        if (lastAutoExportDate == yesterday.toString()) return false
        if (yesterdayCount <= 0) return false
        return nowMillis >= retryAfterMillis
    }
}

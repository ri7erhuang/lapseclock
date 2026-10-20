package com.lapseclock.capture

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * App 私有照片仓库：文件名即时间序，无需数据库。
 * capture_yyyyMMdd_HHmmssSSS.jpg
 */
class PhotoStore(rootDir: File) {

    private val dir = File(rootDir, "captures").apply { mkdirs() }

    private val nameFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS")

    fun newFile(now: Long = System.currentTimeMillis()): File =
        File(dir, "capture_" + nameFmt.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())) + ".jpg")

    fun capturesDir(): File = dir

    fun listAll(): List<File> = dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
        ?.sortedBy { it.name }
        ?: emptyList()

    /** 按本地日期分组（用文件名解析，避开 IO 时间戳） */
    fun groupByDate(): Map<LocalDate, List<File>> =
        listAll().groupBy { parseDate(it.name) }

    fun dates(): List<LocalDate> = groupByDate().keys.sorted()

    fun filesBetweenInclusive(from: LocalDate, to: LocalDate): List<File> {
        val out = ArrayList<File>()
        for ((d, files) in groupByDate()) {
            if (!d.isBefore(from) && !d.isAfter(to)) out.addAll(files)
        }
        return out.sortedBy { it.name }
    }

    data class Counts(val today: Int, val total: Int)

    /**
     * 单次目录遍历同时统计今日/累计张数。
     * 拍照循环每分钟都要刷新计数，避免 countToday()+totalCount() 的两次全量扫描
     * （后者还会 sortOnce + groupBy + 文件名解析日期）。
     */
    fun counts(): Counts {
        val todayPrefix = "capture_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        var today = 0
        var total = 0
        dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }?.forEach { f ->
            total++
            if (f.name.startsWith(todayPrefix)) today++
        }
        return Counts(today, total)
    }

    fun totalCount(): Int = listAll().size

    fun totalBytes(): Long = listAll().sumOf { it.length() }

    fun countOn(date: LocalDate): Int = groupByDate()[date]?.size ?: 0

    fun countToday(): Int = countOn(LocalDate.now())

    fun bytesOn(date: LocalDate): Long = groupByDate()[date]?.sumOf { it.length() } ?: 0L

    fun bytesToday(): Long = bytesOn(LocalDate.now())

    /** 删除 N 天前（不含当天）的旧照片，返回删除数量 */
    fun deleteOlderThan(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = LocalDate.now().minusDays(days.toLong())
        var removed = 0
        for ((d, files) in groupByDate()) {
            if (d.isBefore(cutoff)) {
                files.forEach { if (it.delete()) removed++ }
            }
        }
        return removed
    }

    /**
     * 某一天的概况。first/last 是当天首帧与末帧 —— 两张图并排就能一眼看出「这一天变了什么」，
     * 比任何数字都直观，也是后续胶卷视图的地基。
     */
    data class DayStat(
        val date: LocalDate,
        val count: Int,
        val bytes: Long,
        val first: File,
        val last: File,
        val firstTime: String,
        val lastTime: String,
    )

    /** 最近 [days] 天的概况，新的在前；无照片的日期不占位 */
    fun recentDayStats(days: Int): List<DayStat> {
        val grouped = groupByDate()
        return grouped.entries
            .sortedByDescending { it.key }
            .take(days)
            .map { (date, files) ->
                val sorted = files.sortedBy { it.name }
                val first = sorted.first()
                val last = sorted.last()
                DayStat(
                    date = date,
                    count = sorted.size,
                    bytes = sorted.sumOf { it.length() },
                    first = first,
                    last = last,
                    firstTime = timeOf(first),
                    lastTime = timeOf(last),
                )
            }
    }

    /** 从文件名取 HH:mm，文件名形如 capture_yyyyMMdd_HHmmssSSS.jpg */
    fun timeOf(file: File): String {
        val raw = file.name.removePrefix("capture_")
        return try {
            raw.substring(9, 11) + ":" + raw.substring(11, 13)
        } catch (e: Exception) {
            ""
        }
    }

    /** 全部照片的总字节数 + 天数，第二屏一次扫描拿全 */
    fun overview(): Triple<Int, Long, Int> {
        var total = 0
        var bytes = 0L
        val days = HashSet<String>()
        dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }?.forEach { f ->
            total++
            bytes += f.length()
            days.add(f.name.removePrefix("capture_").take(8))
        }
        return Triple(total, bytes, days.size)
    }

    private fun parseDate(name: String): LocalDate = try {
        LocalDate.parse(name.removePrefix("capture_").substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
    } catch (e: Exception) {
        LocalDate.EPOCH
    }
}

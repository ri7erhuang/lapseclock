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

    private fun parseDate(name: String): LocalDate = try {
        LocalDate.parse(name.removePrefix("capture_").substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
    } catch (e: Exception) {
        LocalDate.EPOCH
    }
}

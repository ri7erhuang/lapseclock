package com.lapseclock.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/**
 * App 私有照片仓库：文件名即时间序，无需数据库。
 * capture_yyyyMMdd_HHmmssSSS.jpg
 *
 * 所有统计都走内存索引：进程启动后在 IO 线程扫一次目录，之后拍照/清理时增量维护。
 * 这样 `listFiles` 只发生一次，而不是每拍一张就遍历整个目录 —— 90 天后的目录
 * 会有十几万个文件，逐张扫描会把拍照循环拖死。
 */
class PhotoStore(rootDir: File) {

    private val dir = File(rootDir, "captures").apply { mkdirs() }

    private val nameFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS")

    private val lock = Any()

    /** 索引是否已建立；界面可据此显示「统计中…」 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    data class Entry(val file: File, val date: LocalDate, val timeOfDay: String, val bytes: Long)

    private class Index {
        val byDate = TreeMap<LocalDate, MutableList<Entry>>()
        var total = 0
        var totalBytes = 0L

        /** 增量插入：新照片基本都排在当天末尾，二分查找只在时钟被改动时才有意义 */
        fun add(e: Entry) {
            val list = byDate.getOrPut(e.date) { ArrayList() }
            var lo = 0
            var hi = list.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].file.name < e.file.name) lo = mid + 1 else hi = mid
            }
            list.add(lo, e)
            total++
            totalBytes += e.bytes
        }
    }

    /** 建索引：一次遍历 + 每天内部排一次序，O(n log n)，不逐条插入 */
    private fun buildIndex(): Index {
        val idx = Index()
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) } ?: emptyArray()
        for (f in files) {
            val date = parseDate(f.name) ?: continue
            idx.byDate.getOrPut(date) { ArrayList() }
                .add(Entry(f, date, timeOf(f.name), f.length()))
        }
        idx.byDate.values.forEach { list -> list.sortBy { it.file.name } }
        idx.total = idx.byDate.values.sumOf { it.size }
        idx.totalBytes = idx.byDate.values.sumOf { list -> list.sumOf { it.bytes } }
        return idx
    }

    /**
     * 建立索引，幂等。调用方应在 IO 线程上调用；内部也会切到 IO。
     * 首次启动十几万文件需要一两秒，期间界面显示统计中，不阻塞主线程。
     */
    suspend fun ensureIndex() {
        if (_ready.value) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (index == null) {
                    index = buildIndex()
                    _ready.value = true
                }
            }
        }
    }

    private var index: Index? = null

    /** 拍照成功后登记新文件，O(log n) */
    fun register(file: File) {
        synchronized(lock) {
            val idx = index ?: return
            val date = parseDate(file.name) ?: return
            idx.add(Entry(file, date, timeOf(file.name), file.length()))
        }
    }

    fun newFile(now: Long = System.currentTimeMillis()): File =
        File(
            dir,
            "capture_" + nameFmt.format(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
            ) + ".jpg"
        )

    fun capturesDir(): File = dir

    /** 剩余可用空间（字节） */
    fun freeBytes(): Long = dir.usableSpace

    fun listAll(): List<File> = synchronized(lock) {
        val idx = index ?: return emptyList()
        buildList {
            for (list in idx.byDate.values) list.forEach { add(it.file) }
        }
    }

    fun dates(): List<LocalDate> = synchronized(lock) {
        index?.byDate?.keys?.toList() ?: emptyList()
    }

    data class Counts(val today: Int, val total: Int)

    /** O(1)：今日张数 + 累计张数 */
    fun counts(): Counts = synchronized(lock) {
        val idx = index ?: return Counts(0, 0)
        Counts(idx.byDate[LocalDate.now()]?.size ?: 0, idx.total)
    }

    fun totalCount(): Int = synchronized(lock) { index?.total ?: 0 }

    fun totalBytes(): Long = synchronized(lock) { index?.totalBytes ?: 0L }

    fun countOn(date: LocalDate): Int = synchronized(lock) {
        index?.byDate?.get(date)?.size ?: 0
    }

    fun countToday(): Int = countOn(LocalDate.now())

    fun bytesOn(date: LocalDate): Long = synchronized(lock) {
        index?.byDate?.get(date)?.sumOf { it.bytes } ?: 0L
    }

    /** 闭区间内的张数；只遍历日期键，不展开文件名 */
    fun countBetween(from: LocalDate, to: LocalDate): Int = synchronized(lock) {
        index?.byDate?.subMap(from, true, to, true)
            ?.values?.sumOf { it.size } ?: 0
    }

    fun filesBetweenInclusive(from: LocalDate, to: LocalDate): List<File> = synchronized(lock) {
        val idx = index ?: return emptyList()
        buildList {
            for ((_, files) in idx.byDate.subMap(from, true, to, true)) {
                files.forEach { add(it.file) }
            }
        }
    }

    /** 最近一张照片，用于试拍预览 */
    fun latest(): File? = synchronized(lock) {
        index?.byDate?.lastEntry()?.value?.lastOrNull()?.file
    }

    /**
     * 删除 N 天前（不含当天）的旧照片，返回删除数量。
     * 纯内存索引 + 文件删除，放在 IO 线程调用。
     */
    fun deleteOlderThan(days: Int): Int {
        if (days <= 0) return 0
        val victims = ArrayList<Entry>()
        synchronized(lock) {
            val idx = index ?: return 0
            // 「保留 7 天」= 今天加前 6 天，共 7 个自然日
            val cutoff = LocalDate.now().minusDays((days - 1).toLong())
            val old = idx.byDate.headMap(cutoff, false)
            for (date in old.keys.toList()) {
                val list = idx.byDate.remove(date) ?: continue
                victims.addAll(list)
            }
            // 先从索引摘掉，文件删除放到锁外做：几十万个文件逐个 delete 时
            // 不会把界面/拍照侧的 count 查询一起堵在锁上
            for (e in victims) {
                idx.total--
                idx.totalBytes -= e.bytes
            }
        }
        var removed = 0
        val failed = ArrayList<Entry>()
        for (e in victims) {
            if (e.file.delete()) removed++ else failed.add(e)
        }
        if (failed.isNotEmpty()) {
            synchronized(lock) {
                index?.let { idx -> failed.forEach { idx.add(it) } }
            }
        }
        return removed
    }

    /**
     * 某一天的概况。first/last 是当天首帧与末帧 —— 两张图并排就能一眼看出
     * 「这一天变了什么」，也是胶卷视图的地基。
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

    /** 最近 [days] 个有照片的日子，新的在前 */
    fun recentDayStats(days: Int): List<DayStat> = synchronized(lock) {
        val idx = index ?: return emptyList()
        idx.byDate.descendingMap().entries.take(days).map { (date, files) ->
            val first = files.first()
            val last = files.last()
            DayStat(
                date = date,
                count = files.size,
                bytes = files.sumOf { it.bytes },
                first = first.file,
                last = last.file,
                firstTime = first.timeOfDay,
                lastTime = last.timeOfDay,
            )
        }
    }

    /** 全部照片的总字节数 + 天数，第二屏一次拿全 */
    fun overview(): Triple<Int, Long, Int> = synchronized(lock) {
        val idx = index ?: return Triple(0, 0L, 0)
        Triple(idx.total, idx.totalBytes, idx.byDate.size)
    }

    /** 从文件名取 HH:mm，文件名形如 capture_yyyyMMdd_HHmmssSSS.jpg */
    private fun timeOf(name: String): String {
        val raw = name.removePrefix("capture_")
        return try {
            raw.substring(9, 11) + ":" + raw.substring(11, 13)
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseDate(name: String): LocalDate? = try {
        LocalDate.parse(
            name.removePrefix("capture_").substring(0, 8),
            DateTimeFormatter.BASIC_ISO_DATE,
        )
    } catch (_: Exception) {
        null
    }
}

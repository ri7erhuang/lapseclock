package com.lapseclock.capture

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PhotoStoreTest {

    private lateinit var root: File
    private lateinit var store: PhotoStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lapseclock-test").toFile()
        store = PhotoStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** capture_yyyyMMdd_HHmmssSSS.jpg */
    private fun addPhoto(date: LocalDate, hhmm: String, bytes: Int = 16): File {
        val name = "capture_" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "_" +
            hhmm.replace(":", "") + "00000.jpg"
        return File(store.capturesDir(), name).apply {
            writeBytes(ByteArray(bytes))
        }
    }

    @Test
    fun `index answers date and range queries without rescanning`() = runBlocking {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        addPhoto(yesterday, "09:00", 10)
        addPhoto(yesterday, "20:00", 20)
        addPhoto(today, "08:00", 5)

        store.ensureIndex()

        assertEquals(2, store.countOn(yesterday))
        assertEquals(1, store.countOn(today))
        assertEquals(3, store.totalCount())
        assertEquals(35L, store.totalBytes())
        assertEquals(listOf(yesterday, today), store.dates())
        assertEquals(3, store.countBetween(yesterday, today))
        assertEquals(2, store.countBetween(yesterday, yesterday))

        val files = store.filesBetweenInclusive(yesterday, today)
        assertEquals(3, files.size)
        assertTrue(files[0].name.startsWith("capture_${yesterday.format(DateTimeFormatter.BASIC_ISO_DATE)}_09"))
        assertTrue(files[2].name.startsWith("capture_${today.format(DateTimeFormatter.BASIC_ISO_DATE)}_08"))

        assertEquals(1, store.counts().today)
        assertEquals(3, store.counts().total)
        assertEquals(Triple(3, 35L, 2), store.overview())
        assertEquals(files.last(), store.latest())

        val day = store.recentDayStats(1).first()
        assertEquals(today, day.date)
        assertEquals(1, day.count)
        assertEquals("08:00", day.firstTime)
    }

    @Test
    fun `deleteOlderThan removes only full days before the cutoff`() = runBlocking {
        val today = LocalDate.now()
        val oldDate = today.minusDays(5)
        val keepDate = today.minusDays(2)
        val old = addPhoto(oldDate, "10:00")
        val keep = addPhoto(keepDate, "10:00")
        addPhoto(today, "10:00")

        store.ensureIndex()
        assertEquals(1, store.deleteOlderThan(3))

        assertFalse(old.exists())
        assertTrue(keep.exists())
        assertEquals(0, store.countOn(oldDate))
        assertEquals(2, store.totalCount())
        assertEquals(2, store.dates().size)
        assertEquals(0, store.deleteOlderThan(0))
    }

    @Test
    fun `register adds a new shot incrementally`() = runBlocking {
        val today = LocalDate.now()
        store.ensureIndex()
        assertEquals(0, store.totalCount())

        val file = addPhoto(today, "12:34")
        store.register(file)

        assertEquals(1, store.countOn(today))
        assertEquals(1, store.totalCount())
        assertEquals("12:34", store.recentDayStats(1).first().firstTime)
        assertEquals(file, store.latest())
    }

    @Test
    fun `malformed files are ignored`() = runBlocking {
        File(store.capturesDir(), "not-a-capture.jpg").writeText("x")
        File(store.capturesDir(), "capture_broken.jpg").writeText("x")
        addPhoto(LocalDate.now(), "11:11")

        store.ensureIndex()
        assertEquals(1, store.totalCount())
    }

    @Test
    fun `ensureIndex is idempotent`() = runBlocking {
        addPhoto(LocalDate.now(), "13:00")
        store.ensureIndex()
        store.ensureIndex()
        assertEquals(1, store.totalCount())
    }
}

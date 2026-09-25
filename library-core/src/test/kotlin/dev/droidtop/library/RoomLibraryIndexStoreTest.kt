package dev.droidtop.library

import android.app.Application
import androidx.room.Room
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoomLibraryIndexStoreTest {

    private fun inMemoryDb(): LibraryIndexDatabase =
        Room.inMemoryDatabaseBuilder(
            Application(),
            LibraryIndexDatabase::class.java,
        ).allowMainThreadQueries().build()

    private fun recordDir(): File = Files.createTempDirectory("droidtop-records").toFile()

    private fun entry(id: String = "game:one", title: String = "One"): LibraryEntry =
        LibraryEntry(id = id, title = title, kind = LibraryEntryKind.RENPY)

    @Test
    fun `save writes a new segment and load reads it back`() = runBlocking {
        val db = inMemoryDb()
        val records = FileGameRecordStore(recordDir())
        val store = RoomLibraryIndexStore(db, records)

        val segment = ScanStep.Segment(key = "part-a", root = "/games", entries = listOf(entry()))
        store.save("provider1", LibrarySlice(listOf(segment)))

        val loaded = store.load("provider1")
        assertNotNull(loaded)
        assertEquals(1, loaded!!.segments.size)
        assertEquals("part-a", loaded.segments[0].key)
        assertEquals(1, loaded.segments[0].entries.size)
        assertEquals("game:one", loaded.segments[0].entries[0].id)
    }

    @Test
    fun `save diffs against stored segment and updates changed entries`() = runBlocking {
        val db = inMemoryDb()
        val records = FileGameRecordStore(recordDir())
        val store = RoomLibraryIndexStore(db, records)

        val first = entry("game:one", "First")
        val updated = entry("game:one", "Updated")
        val segmentFirst = ScanStep.Segment(key = "part-1", root = "/games", entries = listOf(first))

        store.save("provider1", LibrarySlice(listOf(segmentFirst)))

        val segmentUpdated = ScanStep.Segment(
            key = "part-1", root = "/games",
            entries = listOf(updated.copy(kind = LibraryEntryKind.RENPY)),
        )
        store.save("provider1", LibrarySlice(listOf(segmentUpdated)))

        val loaded = store.load("provider1")
        assertNotNull(loaded)
        assertEquals(1, loaded!!.segments.size)
        assertEquals("Updated", loaded.segments[0].entries[0].title)
    }

    @Test
    fun `delete removes records and rows when provider no longer reports a segment`() = runBlocking {
        val db = inMemoryDb()
        val records = FileGameRecordStore(recordDir())
        val store = RoomLibraryIndexStore(db, records)

        val deleted = entry("deleted", "Deleted")
        val segment = ScanStep.Segment(key = "part-old", root = "/games", entries = listOf(deleted))
        store.save("provider1", LibrarySlice(listOf(segment)))

        // Verify pre-condition
        val before = store.load("provider1")
        assertNotNull(before)
        assertEquals(1, before!!.segments.size)

        store.save("provider1", LibrarySlice(emptyList()))

        val after = store.load("provider1")
        assertNull(after)
    }

    @Test
    fun `concurrent saves for the same provider are serialized by one writer per provider`() = runBlocking {
        val db = inMemoryDb()
        val records = FileGameRecordStore(recordDir())
        val store = RoomLibraryIndexStore(db, records)

        val segmentA = ScanStep.Segment("a", "/games", entries = listOf(entry("a", "A")))
        val segmentB = ScanStep.Segment("b", "/games", entries = listOf(entry("b", "B")))

        store.save("provider1", LibrarySlice(listOf(segmentA, segmentB)))

        val jobs = List(10) { index ->
            async {
                if (index % 2 == 0) {
                    store.save("provider1", LibrarySlice(listOf(segmentA)))
                } else {
                    store.save("provider1", LibrarySlice(listOf(segmentB)))
                }
            }
        }
        jobs.awaitAll()

        val loaded = store.load("provider1")
        assertNotNull(loaded)
        // After concurrent serialized saves, exactly one segment remains.
        assertEquals(1, loaded!!.segments.size)
        val remainingKey = loaded.segments[0].key
        assertTrue(remainingKey == "a" || remainingKey == "b")
    }
}

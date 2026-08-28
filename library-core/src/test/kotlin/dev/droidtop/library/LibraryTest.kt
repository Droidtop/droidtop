package dev.droidtop.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider(
    kind: LibraryEntryKind,
    private val entries: List<LibraryEntry>,
    private val failLaunch: Boolean = false,
) : LibraryProvider {
    override val kinds = setOf(kind)
    val launched = mutableListOf<LibraryEntry>()

    override suspend fun scan(): List<LibraryEntry> = entries
    override suspend fun launch(entry: LibraryEntry) {
        if (failLaunch) error("launch failed")
        launched += entry
    }
}

private class FakePlayHistoryStore : PlayHistoryStore {
    private val records = mutableMapOf<String, PlayHistoryRecord>()
    val recordCalls = mutableListOf<String>()

    override suspend fun recordPlay(id: String, epochMs: Long) {
        recordCalls += id
        val previousCount = records[id]?.playCount ?: 0
        records[id] = PlayHistoryRecord(epochMs, previousCount + 1)
    }

    override suspend fun getAll(ids: Collection<String>): Map<String, PlayHistoryRecord> =
        records.filterKeys { it in ids }
}

class LibraryTest {
    private val nativeEntry = LibraryEntry(id = "com.example.app", title = "Example App", kind = LibraryEntryKind.NATIVE_ANDROID_APP)
    private val wineEntry = LibraryEntry(id = "wine:notepad", title = "Notepad", kind = LibraryEntryKind.WINE_PROFILE)

    @Test
    fun `scanAll aggregates entries across every registered provider`() = runBlocking {
        val nativeProvider = FakeProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(nativeEntry))
        val wineProvider = FakeProvider(LibraryEntryKind.WINE_PROFILE, listOf(wineEntry))
        val library = Library(listOf(nativeProvider, wineProvider))

        val entries = library.scanAll()

        assertEquals(2, entries.size)
        assertTrue(entries.contains(nativeEntry))
        assertTrue(entries.contains(wineEntry))
    }

    @Test
    fun `launch dispatches to the provider matching the entry's kind, not just the first one`() = runBlocking {
        val nativeProvider = FakeProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(nativeEntry))
        val wineProvider = FakeProvider(LibraryEntryKind.WINE_PROFILE, listOf(wineEntry))
        // Deliberately registered native-provider first: if Library.launch ever
        // regressed to "always use providers.first()" instead of matching on
        // kind, this would launch wineEntry via the wrong provider and this
        // assertion would fail.
        val library = Library(listOf(nativeProvider, wineProvider))

        library.launch(wineEntry)

        assertEquals(listOf(wineEntry), wineProvider.launched)
        assertTrue(nativeProvider.launched.isEmpty())
    }

    @Test
    fun `launch records real play history, and a successful scan reflects it`() = runBlocking {
        val nativeProvider = FakeProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(nativeEntry))
        val playHistory = FakePlayHistoryStore()
        val library = Library(listOf(nativeProvider), playHistory)

        library.launch(nativeEntry)
        library.launch(nativeEntry)
        val entries = library.scanAll()

        assertEquals(listOf(nativeEntry.id, nativeEntry.id), playHistory.recordCalls)
        val rescored = entries.single { it.id == nativeEntry.id }
        assertEquals(2, rescored.playCount)
        assertTrue(rescored.lastPlayedEpochMs != null)
    }

    @Test
    fun `a failed launch is never recorded as a real play`() = runBlocking {
        val failingProvider = FakeProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(nativeEntry), failLaunch = true)
        val playHistory = FakePlayHistoryStore()
        val library = Library(listOf(failingProvider), playHistory)

        try {
            library.launch(nativeEntry)
        } catch (t: Throwable) {
            // Expected -- the fake provider's launch() always throws.
        }

        assertTrue(playHistory.recordCalls.isEmpty())
    }
}

package dev.droidtop.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider(
    override val kind: LibraryEntryKind,
    private val entries: List<LibraryEntry>,
) : LibraryProvider {
    val launched = mutableListOf<LibraryEntry>()

    override suspend fun scan(): List<LibraryEntry> = entries
    override suspend fun launch(entry: LibraryEntry) {
        launched += entry
    }
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
}

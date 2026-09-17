package dev.droidtop.library

import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CountingProvider(
    kind: LibraryEntryKind,
    private val result: List<LibraryEntry>,
    override val indexed: Boolean = true,
) : LibraryProvider {
    override val kinds = setOf(kind)
    var scans = 0
    override suspend fun scan(): List<LibraryEntry> {
        scans++
        return result
    }
    override suspend fun launch(entry: LibraryEntry) {}
}

private class FakeIndexStore(initial: Map<String, List<LibraryEntry>> = emptyMap()) : LibraryIndexStore {
    val slices = initial.toMutableMap()
    override suspend fun load(providerKey: String): List<LibraryEntry>? = slices[providerKey]
    override suspend fun save(providerKey: String, entries: List<LibraryEntry>) {
        slices[providerKey] = entries
    }
}

class LibraryIndexTest {
    private val known = LibraryEntry(id = "renpy:known", title = "Known", kind = LibraryEntryKind.RENPY)
    private val found = LibraryEntry(id = "renpy:found", title = "Found", kind = LibraryEntryKind.RENPY)

    @Test
    fun `a start with an index shows it and walks nothing`() = runBlocking {
        val provider = CountingProvider(LibraryEntryKind.RENPY, listOf(found))
        val store = FakeIndexStore(mapOf(provider.indexKey to listOf(known)))
        val library = Library(listOf(provider), index = store)

        val snapshots = library.scanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList()

        assertEquals(listOf(listOf(known)), snapshots)
        assertEquals(0, provider.scans)
        assertEquals(listOf(known), store.slices[provider.indexKey])
    }

    @Test
    fun `a rescan keeps the index on screen until the walk completes, then replaces and saves it`() = runBlocking {
        val provider = CountingProvider(LibraryEntryKind.RENPY, listOf(found))
        val store = FakeIndexStore(mapOf(provider.indexKey to listOf(known)))
        val library = Library(listOf(provider), index = store)

        val snapshots = library.rescanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList()

        // First the index, then the completed walk; never a partial in between.
        assertEquals(listOf(listOf(known), listOf(found)), snapshots)
        assertEquals(1, provider.scans)
        assertEquals(listOf(found), store.slices[provider.indexKey])
    }

    @Test
    fun `a first run walks and the completed walk becomes the index`() = runBlocking {
        val provider = CountingProvider(LibraryEntryKind.RENPY, listOf(found))
        val store = FakeIndexStore()
        val library = Library(listOf(provider), index = store)

        val snapshots = library.scanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList()

        assertEquals(listOf(listOf(found)), snapshots)
        assertEquals(1, provider.scans)
        assertEquals(listOf(found), store.slices[provider.indexKey])
    }

    @Test
    fun `a provider that is not indexed always walks and is never saved`() = runBlocking {
        val app = LibraryEntry(id = "com.example", title = "App", kind = LibraryEntryKind.NATIVE_ANDROID_APP)
        val provider = CountingProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(app), indexed = false)
        val store = FakeIndexStore(mapOf(provider.indexKey to listOf(app.copy(id = "stale"))))
        val library = Library(listOf(provider), index = store)

        val snapshots = library.scanKindsProgressive(setOf(LibraryEntryKind.NATIVE_ANDROID_APP)).toList()

        assertEquals(listOf(listOf(app)), snapshots)
        assertEquals(1, provider.scans)
        assertEquals(listOf(app.copy(id = "stale")), store.slices[provider.indexKey])
    }

    @Test
    fun `the background scan publishes the index at once`() = runBlocking {
        val provider = CountingProvider(LibraryEntryKind.RENPY, listOf(found))
        val library = Library(listOf(provider), index = FakeIndexStore(mapOf(provider.indexKey to listOf(known))))
        val state = library.backgroundScanState(provider.kinds)

        library.scanInBackground(provider.kinds)
        withTimeout(5_000) {
            while (state.value != listOf(known)) delay(1)
        }
        assertEquals(0, provider.scans)
    }

    @Test
    fun `the file store round-trips an entry with every nested type and treats a torn file as no slice`() = runBlocking {
        val dir = Files.createTempDirectory("droidtop-index").toFile()
        val store = FileLibraryIndexStore(dir)
        val entry = LibraryEntry(
            id = "pc:steam:440",
            title = "Team Fortress 2",
            kind = LibraryEntryKind.WINE_PROFILE,
            mediaLocator = GameMediaLocator(gamesRoot = "/roots/games", system = "pc", baseName = "tf2"),
            rating = 0.8f,
            favorite = true,
            pcInfo = PcInfo(
                source = "steam",
                storeId = "440",
                installed = true,
                sizeBytes = 12_345L,
                installPath = "/roots/games/Steam/steamapps/common/Team Fortress 2",
                compatibility = PcCompatibility(
                    averageRating = 4.5f,
                    playableReports = 3,
                    gpuPlayableReports = 2,
                    hasBeenTried = true,
                    reportedNotWorking = false,
                ),
            ),
        )

        assertNull(store.load("EngineGameProvider"))
        store.save("EngineGameProvider", listOf(entry))
        assertEquals(listOf(entry), store.load("EngineGameProvider"))

        // A torn write is not an error the user sees: it is a walk.
        java.io.File(dir, "EngineGameProvider.json").writeText("{\"formatVersion\": 1, \"entries\": [")
        assertNull(store.load("EngineGameProvider"))
        assertTrue(dir.deleteRecursively())
    }
}

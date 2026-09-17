package dev.droidtop.library

import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A provider that answers as a whole, the way a source with no folders does. */
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

/** A provider that walks folders: one [ScanStep] per finished folder, then the root. */
private class FolderProvider(
    kind: LibraryEntryKind,
    private val root: String,
    private val folders: List<Pair<String, List<LibraryEntry>>>,
    /** How many folders finish before the walk blows up; -1 means it finishes. */
    private val failAfterFolders: Int = -1,
) : LibraryProvider {
    override val kinds = setOf(kind)
    var walks = 0
    override suspend fun scan(): List<LibraryEntry> = folders.flatMap { it.second }
    override suspend fun launch(entry: LibraryEntry) {}
    override fun scanProgressive(): Flow<ScanStep> = flow {
        walks++
        folders.forEachIndexed { index, (folder, entries) ->
            if (index == failAfterFolders) error("this folder blew up")
            emit(ScanStep.Segment(key = folder, root = root, entries = entries))
        }
        if (failAfterFolders >= 0) error("the root never finished")
        emit(ScanStep.RootDone(root, folders.map { it.first }))
    }
}

private class FakeIndexStore(initial: Map<String, LibrarySlice> = emptyMap()) : LibraryIndexStore {
    val slices = initial.toMutableMap()
    var saves = 0
    override suspend fun load(providerKey: String): LibrarySlice? = slices[providerKey]
    override suspend fun save(providerKey: String, slice: LibrarySlice) {
        saves++
        slices[providerKey] = slice
    }
}

private fun sliceOf(vararg segments: ScanStep.Segment) = LibrarySlice(segments.toList())

class LibraryIndexTest {
    private val known = LibraryEntry(id = "renpy:known", title = "Known", kind = LibraryEntryKind.RENPY)
    private val found = LibraryEntry(id = "renpy:found", title = "Found", kind = LibraryEntryKind.RENPY)

    private val root = "/games"
    private val adult = "/games/adult"
    private val steam = "/games/steam"
    private fun game(path: String) = LibraryEntry(id = path, title = path.substringAfterLast('/'), kind = LibraryEntryKind.RENPY)

    @Test
    fun `a start with an index shows it and walks nothing`() = runBlocking {
        val provider = CountingProvider(LibraryEntryKind.RENPY, listOf(found))
        val store = FakeIndexStore(mapOf(provider.indexKey to sliceOf(ScanStep.Segment(ScanStep.WHOLE, entries = listOf(known)))))
        val library = Library(listOf(provider), index = store)

        val snapshots = library.scanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList()

        assertEquals(listOf(listOf(known)), snapshots)
        assertEquals(0, provider.scans)
    }

    @Test
    fun `a first run walks and the completed walk becomes the index`() = runBlocking {
        val provider = CountingProvider(LibraryEntryKind.RENPY, listOf(found))
        val store = FakeIndexStore()
        val library = Library(listOf(provider), index = store)

        val snapshots = library.scanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList()

        assertEquals(listOf(listOf(found)), snapshots)
        assertEquals(1, provider.scans)
        assertEquals(listOf(found), store.slices[provider.indexKey]?.entries())
    }

    @Test
    fun `a provider that is not indexed always walks and is never saved`() = runBlocking {
        val app = LibraryEntry(id = "com.example", title = "App", kind = LibraryEntryKind.NATIVE_ANDROID_APP)
        val stale = sliceOf(ScanStep.Segment(ScanStep.WHOLE, entries = listOf(app.copy(id = "stale"))))
        val provider = CountingProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(app), indexed = false)
        val store = FakeIndexStore(mapOf(provider.indexKey to stale))
        val library = Library(listOf(provider), index = store)

        val snapshots = library.scanKindsProgressive(setOf(LibraryEntryKind.NATIVE_ANDROID_APP)).toList()

        assertEquals(listOf(listOf(app)), snapshots)
        assertEquals(1, provider.scans)
        assertEquals(stale, store.slices[provider.indexKey])
    }

    @Test
    fun `a finished folder replaces only its own entries`() = runBlocking {
        val oldAdult = game("$adult/Game v0.3")
        val newAdult = game("$adult/Game v0.4")
        val steamGame = game("$steam/Other")
        val provider = FolderProvider(
            LibraryEntryKind.RENPY,
            root,
            listOf(adult to listOf(newAdult), steam to listOf(steamGame)),
        )
        val store = FakeIndexStore(
            mapOf(
                provider.indexKey to sliceOf(
                    ScanStep.Segment(adult, root, listOf(oldAdult)),
                    ScanStep.Segment(steam, root, listOf(steamGame)),
                ),
            ),
        )
        val library = Library(listOf(provider), index = store)

        val snapshots = library.rescanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList()

        // The first walked folder is published before the second is
        // walked, and it replaced only its own: steam's game is in every
        // snapshot, untouched.
        assertTrue(snapshots.first().map { it.id }.contains(steamGame.id))
        val last = snapshots.last()
        assertEquals(setOf(newAdult.id, oldAdult.id, steamGame.id), last.map { it.id }.toSet())
        assertFalse(last.first { it.id == steamGame.id }.missing)
        assertTrue(last.first { it.id == oldAdult.id }.missing)
        assertEquals(1, store.slices[provider.indexKey]?.segments?.count { it.key == adult })
    }

    @Test
    fun `a walk that blows up leaves the folders it never reached alone`() = runBlocking {
        val steamGame = game("$steam/Other")
        val newAdult = game("$adult/Game v0.4")
        val provider = FolderProvider(
            LibraryEntryKind.RENPY,
            root,
            listOf(adult to listOf(newAdult), steam to emptyList()),
            failAfterFolders = 1,
        )
        val store = FakeIndexStore(
            mapOf(
                provider.indexKey to sliceOf(
                    ScanStep.Segment(adult, root, listOf(game("$adult/Game v0.3"))),
                    ScanStep.Segment(steam, root, listOf(steamGame)),
                ),
            ),
        )
        val library = Library(listOf(provider), index = store)

        val last = library.rescanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList().last()

        // No RootDone ever arrived, so "steam was not walked" is not read
        // as "steam is gone": its game is still there and still found.
        assertFalse(last.first { it.id == steamGame.id }.missing)
        assertTrue(last.map { it.id }.contains(newAdult.id))
    }

    @Test
    fun `a game a walked folder no longer holds is missing, and keeps its facts`() = runBlocking {
        val gone = game("$adult/Game v0.3").copy(favorite = true, playCount = 7, description = "scraped")
        val provider = FolderProvider(LibraryEntryKind.RENPY, root, listOf(adult to emptyList()))
        val store = FakeIndexStore(mapOf(provider.indexKey to sliceOf(ScanStep.Segment(adult, root, listOf(gone)))))
        val library = Library(listOf(provider), index = store)

        val last = library.rescanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList().last()

        assertEquals(listOf(gone.copy(missing = true)), last)
        assertEquals(listOf(gone.copy(missing = true)), store.slices[provider.indexKey]?.entries())
    }

    @Test
    fun `a folder that is no longer under its root leaves its games missing, not dropped`() = runBlocking {
        val gone = game("$steam/Other")
        val provider = FolderProvider(LibraryEntryKind.RENPY, root, listOf(adult to listOf(game("$adult/Here"))))
        val store = FakeIndexStore(
            mapOf(provider.indexKey to sliceOf(ScanStep.Segment(steam, root, listOf(gone)))),
        )
        val library = Library(listOf(provider), index = store)

        val last = library.rescanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList().last()

        assertTrue(last.first { it.id == gone.id }.missing)
        assertFalse(last.first { it.id == "$adult/Here" }.missing)
    }

    @Test
    fun `a missing game that is found again is simply found again`() = runBlocking {
        val back = game("$adult/Game v0.3")
        val provider = FolderProvider(LibraryEntryKind.RENPY, root, listOf(adult to listOf(back)))
        val store = FakeIndexStore(
            mapOf(provider.indexKey to sliceOf(ScanStep.Segment(adult, root, listOf(back.copy(missing = true))))),
        )
        val library = Library(listOf(provider), index = store)

        val last = library.rescanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList().last()

        assertEquals(listOf(back), last)
    }

    @Test
    fun `removing a root removes its entries outright`() = runBlocking {
        val elsewhere = game("/other/Game")
        val provider = FolderProvider(LibraryEntryKind.RENPY, root, emptyList())
        val store = FakeIndexStore(
            mapOf(
                provider.indexKey to sliceOf(
                    ScanStep.Segment(adult, root, listOf(game("$adult/Here"))),
                    ScanStep.Segment("/other", "/other", listOf(elsewhere)),
                    ScanStep.Segment(ScanStep.WHOLE, null, listOf(known)),
                ),
            ),
        )
        val library = Library(listOf(provider), index = store)

        library.keepOnlyRoots(setOf("/other"))

        val left = store.slices[provider.indexKey]?.entries().orEmpty()
        // The removed root's game is gone, not missing; a part under no
        // root at all (a store's own database) is untouched.
        assertEquals(setOf(elsewhere.id, known.id), left.map { it.id }.toSet())
    }

    @Test
    fun `the background scan publishes the index at once`() = runBlocking {
        val provider = CountingProvider(LibraryEntryKind.RENPY, listOf(found))
        val store = FakeIndexStore(mapOf(provider.indexKey to sliceOf(ScanStep.Segment(ScanStep.WHOLE, entries = listOf(known)))))
        val library = Library(listOf(provider), index = store)
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
            missing = true,
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
        val slice = sliceOf(ScanStep.Segment(key = "/roots/games/Steam", root = "/roots/games", entries = listOf(entry)))

        assertNull(store.load("EngineGameProvider"))
        store.save("EngineGameProvider", slice)
        assertEquals(slice, store.load("EngineGameProvider"))

        // A torn write is not an error the user sees: it is a walk.
        java.io.File(dir, "EngineGameProvider.json").writeText("{\"formatVersion\": 2, \"slice\": {")
        assertNull(store.load("EngineGameProvider"))
        assertTrue(dir.deleteRecursively())
    }
}

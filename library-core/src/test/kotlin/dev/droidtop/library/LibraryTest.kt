package dev.droidtop.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    override suspend fun moveTo(fromId: String, toId: String) {
        val from = records.remove(fromId) ?: return
        val to = records[toId]
        records[toId] = PlayHistoryRecord(
            lastPlayedEpochMs = maxOf(from.lastPlayedEpochMs, to?.lastPlayedEpochMs ?: 0L),
            playCount = from.playCount + (to?.playCount ?: 0),
        )
    }
}

private class FakeFavoritesStore : FavoritesStore {
    val ids = mutableSetOf<String>()
    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) ids += id else ids -= id
    }
    override suspend fun getAll(ids: Collection<String>): Set<String> = this.ids.filter { it in ids }.toSet()
    override suspend fun moveTo(fromId: String, toId: String) {
        if (ids.remove(fromId)) ids += toId
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

    @Test
    fun `a favourite on a non-ROM game is kept by the library and comes back with the scan`() = runBlocking {
        val provider = FakeProvider(LibraryEntryKind.WINE_PROFILE, listOf(wineEntry))
        val favorites = FakeFavoritesStore()
        val library = Library(listOf(provider), favorites = favorites)

        // Before this store existed, toggleFavorite answered null for
        // every kind but a console ROM, and X in the gamelist did nothing
        // for 151 of 158 games on the rig (build 549).
        assertEquals(true, library.toggleFavorite(wineEntry))
        assertEquals(setOf(wineEntry.id), favorites.ids)

        val entries = library.scanAll()
        assertTrue(entries.single { it.id == wineEntry.id }.favorite)

        // Toggling the scanned (now favourite) entry turns it off again.
        assertEquals(false, library.toggleFavorite(entries.single { it.id == wineEntry.id }))
        assertTrue(favorites.ids.isEmpty())
    }

    @Test
    fun `a game that is not a favourite is left exactly as its provider reported it`() = runBlocking {
        val provider = FakeProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(nativeEntry))
        val library = Library(listOf(provider), favorites = FakeFavoritesStore())

        val entries = library.scanAll()

        assertEquals(nativeEntry, entries.single())
    }

    // --- folding a missing game into the game that replaced it (7g) ------

    private val missing = LibraryEntry(
        id = "/games/adult/Game v0.3",
        title = "Game",
        kind = LibraryEntryKind.RENPY,
        missing = true,
    )
    private val replacement = LibraryEntry(id = "/games/adult/Game v0.4", title = "Game", kind = LibraryEntryKind.RENPY)

    @Test
    fun `the fold moves play history, the favourite and what a provider knows`() = runBlocking {
        val provider = FoldingProvider(LibraryEntryKind.RENPY, listOf(replacement))
        val playHistory = FakePlayHistoryStore()
        val favorites = FakeFavoritesStore()
        val index = FakeFoldIndexStore(
            mutableMapOf(
                provider.indexKey to LibrarySlice(
                    listOf(ScanStep.Segment("/games/adult", "/games", listOf(missing, replacement))),
                ),
            ),
        )
        val library = Library(listOf(provider), playHistory, favorites, index)
        playHistory.recordPlay(missing.id, 1_000L)
        playHistory.recordPlay(missing.id, 2_000L)
        favorites.setFavorite(missing.id, true)

        assertTrue(library.replaceMissing(missing, replacement))

        // Everything the library knew about the old path is now known
        // about the new one, and the old entry is out of the index.
        assertEquals(2, playHistory.getAll(listOf(replacement.id))[replacement.id]?.playCount)
        assertEquals(2_000L, playHistory.getAll(listOf(replacement.id))[replacement.id]?.lastPlayedEpochMs)
        assertTrue(playHistory.getAll(listOf(missing.id)).isEmpty())
        assertEquals(setOf(replacement.id), favorites.ids)
        assertEquals(listOf(missing.id to replacement.id), provider.moved)
        assertEquals(listOf(replacement.id), index.slices[provider.indexKey]?.entries()?.map { it.id })
    }

    @Test
    fun `an entry that is not missing is never folded away`() = runBlocking {
        val provider = FoldingProvider(LibraryEntryKind.RENPY, listOf(replacement))
        val library = Library(listOf(provider), FakePlayHistoryStore(), FakeFavoritesStore())

        assertFalse(library.replaceMissing(replacement, missing.copy(missing = false)))
        assertTrue(provider.moved.isEmpty())
    }

    private val renpyGame = LibraryEntry(id = "/games/renpy/Known", title = "Known", kind = LibraryEntryKind.RENPY)

    @Test
    fun `a launch by id reads the game's record and walks nothing`() = runBlocking {
        val provider = KeyedProvider("engine", LibraryEntryKind.RENPY, listOf(renpyGame))
        val records = FakeRecordStore(GameRecord(entry = renpyGame, provider = "engine"))
        val library = Library(listOf(provider), records = records)

        assertEquals(LaunchResult.Launched, library.launch(renpyGame.id))

        assertEquals(listOf(renpyGame), provider.launched)
        assertEquals(0, provider.scans)
    }

    @Test
    fun `a launch by id with no record finds the game in the index and walks nothing`() = runBlocking {
        val provider = KeyedProvider("engine", LibraryEntryKind.RENPY, listOf(renpyGame))
        val index = FakeFoldIndexStore(
            mutableMapOf("engine" to LibrarySlice(listOf(ScanStep.Segment(ScanStep.WHOLE, entries = listOf(renpyGame))))),
        )
        val library = Library(listOf(provider), index = index)

        assertEquals(LaunchResult.Launched, library.launch(renpyGame.id))

        assertEquals(listOf(renpyGame), provider.launched)
        assertEquals(0, provider.scans)
    }

    @Test
    fun `a launch by id the index cannot answer walks only the providers that could hold it`() = runBlocking {
        // The engine provider's slice is known and does not list the app;
        // the app list is outside the index, so only it is walked.
        val engine = KeyedProvider("engine", LibraryEntryKind.RENPY, listOf(renpyGame))
        val apps = KeyedProvider("apps", LibraryEntryKind.NATIVE_ANDROID_APP, listOf(nativeEntry), indexed = false)
        val index = FakeFoldIndexStore(
            mutableMapOf("engine" to LibrarySlice(listOf(ScanStep.Segment(ScanStep.WHOLE, entries = listOf(renpyGame))))),
        )
        val library = Library(listOf(engine, apps), index = index)

        assertEquals(LaunchResult.Launched, library.launch(nativeEntry.id))

        assertEquals(listOf(nativeEntry), apps.launched)
        assertEquals(1, apps.scans)
        assertEquals(0, engine.scans)
    }

    @Test
    fun `a launch by id refuses a game marked missing`() = runBlocking {
        val provider = KeyedProvider("engine", LibraryEntryKind.RENPY, listOf(renpyGame))
        val records = FakeRecordStore(GameRecord(entry = renpyGame.copy(missing = true), provider = "engine"))
        val playHistory = FakePlayHistoryStore()
        val library = Library(listOf(provider), playHistory, records = records)

        val result = library.launch(renpyGame.id)

        assertTrue(result is LaunchResult.Refused)
        assertTrue((result as LaunchResult.Refused).reason.contains("missing"))
        assertTrue(provider.launched.isEmpty())
        assertTrue(playHistory.recordCalls.isEmpty())
    }

    @Test
    fun `a launch by an unknown id is refused, not thrown`() = runBlocking {
        val provider = KeyedProvider("engine", LibraryEntryKind.RENPY, listOf(renpyGame))
        val library = Library(listOf(provider))

        val result = library.launch("/games/renpy/Nobody")

        assertTrue(result is LaunchResult.Refused)
        assertTrue(provider.launched.isEmpty())
    }

    @Test
    fun `a launch by id whose provider throws is refused, not thrown`() = runBlocking {
        val failing = FakeProvider(LibraryEntryKind.NATIVE_ANDROID_APP, listOf(nativeEntry), failLaunch = true)
        val library = Library(listOf(failing))

        assertTrue(library.launch(nativeEntry.id) is LaunchResult.Refused)
    }
}

/** A provider with its own index key, counting its walks. */
private class KeyedProvider(
    override val indexKey: String,
    kind: LibraryEntryKind,
    private val entries: List<LibraryEntry>,
    override val indexed: Boolean = true,
) : LibraryProvider {
    override val kinds = setOf(kind)
    var scans = 0
    val launched = mutableListOf<LibraryEntry>()
    override suspend fun scan(): List<LibraryEntry> {
        scans++
        return entries
    }
    override suspend fun launch(entry: LibraryEntry) {
        launched += entry
    }
}

private class FakeRecordStore(vararg initial: GameRecord) : GameRecordStore {
    private val records = initial.associateBy { it.entry.id }.toMutableMap()
    override fun get(id: String): GameRecord? = records[id]
    override fun put(record: GameRecord) {
        records[record.entry.id] = record
    }
    override fun delete(id: String) {
        records.remove(id)
    }
    override fun all(): List<GameRecord> = records.values.toList()
}

/** A provider that keeps facts of its own, like the ROM provider's database does. */
private class FoldingProvider(
    kind: LibraryEntryKind,
    private val entries: List<LibraryEntry>,
) : LibraryProvider, EntryFactsOwner {
    override val kinds = setOf(kind)
    val moved = mutableListOf<Pair<String, String>>()
    override suspend fun scan(): List<LibraryEntry> = entries
    override suspend fun launch(entry: LibraryEntry) {}
    override suspend fun moveEntryFacts(fromId: String, toId: String) {
        moved += fromId to toId
    }
}

private class FakeFoldIndexStore(val slices: MutableMap<String, LibrarySlice>) : LibraryIndexStore {
    override suspend fun load(providerKey: String): LibrarySlice? = slices[providerKey]
    override suspend fun save(providerKey: String, slice: LibrarySlice) {
        slices[providerKey] = slice
    }
}

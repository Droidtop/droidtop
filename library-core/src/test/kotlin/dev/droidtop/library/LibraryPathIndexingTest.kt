package dev.droidtop.library

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Targeted indexing (docs/SPEC.md 7g, Droidtop/tracker#354): a file or folder droidtop or a plugin changed is
 * brought into the index by looking at that path, with no walk. The providers here are fakes whose only job
 * is to answer `indexPath`; what is under test is the library's side: which providers are asked about which
 * path, what of the slice is replaced, what is kept, and what a removal leaves behind.
 */
class LibraryPathIndexingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeIndex : LibraryIndexStore {
        val slices = HashMap<String, LibrarySlice>()
        var saves = 0
        override suspend fun load(providerKey: String): LibrarySlice? = slices[providerKey]
        override suspend fun save(providerKey: String, slice: LibrarySlice) {
            saves++
            slices[providerKey] = slice
        }
    }

    private class FakeRecords : GameRecordStore {
        val deleted = ArrayList<String>()
        override fun get(id: String): GameRecord? = null
        override fun put(record: GameRecord) {}
        override fun delete(id: String) {
            deleted += id
        }
        override fun all(): List<GameRecord> = emptyList()
    }

    /** Walks one root once; afterwards only ever asked about paths. */
    private class PathProvider(
        kind: LibraryEntryKind,
        private val root: String,
        private val parts: List<Pair<String, List<LibraryEntry>>>,
        override val indexesPathsEarly: Boolean = false,
        override val indexKey: String = "PathProvider",
        private val answer: (File) -> List<PathIndexing> = { emptyList() },
    ) : LibraryProvider {
        override val kinds = setOf(kind)
        var walks = 0
        val asked = ArrayList<String>()
        override suspend fun scan(): List<LibraryEntry> = parts.flatMap { it.second }
        override suspend fun launch(entry: LibraryEntry) {}
        override fun scanProgressive(): Flow<ScanStep> = flow {
            walks++
            parts.forEach { (key, entries) -> emit(ScanStep.Segment(key, root, entries)) }
            emit(ScanStep.RootDone(root, parts.map { it.first }))
        }
        override suspend fun indexPath(path: File): List<PathIndexing> {
            asked += path.path
            return answer(path)
        }
    }

    private fun rom(path: String) = LibraryEntry(id = path, title = File(path).nameWithoutExtension, kind = LibraryEntryKind.CONSOLE_ROM, systemId = "snes")

    private fun folderGame(path: String, id: String = "folder:CUSTOM_GAME_7") = LibraryEntry(
        id = id,
        title = File(path).name,
        kind = LibraryEntryKind.WINE_PROFILE,
        systemId = "pc",
        pcInfo = PcInfo(installed = true, installPath = path),
    )

    private fun touch(path: String): File = File(temp.root, path).also {
        it.parentFile?.mkdirs()
        it.writeText("x")
    }

    private suspend fun walked(vararg providers: LibraryProvider, index: FakeIndex = FakeIndex(), records: FakeRecords = FakeRecords()): Triple<Library, FakeIndex, FakeRecords> {
        val library = Library(providers.toList(), index = index, records = records)
        library.scanKindsProgressive(providers.flatMap { it.kinds }.toSet()).toList()
        return Triple(library, index, records)
    }

    private fun ids(index: FakeIndex, provider: LibraryProvider) = index.slices[provider.indexKey]!!.entries().map { it.id }.sorted()

    @Test
    fun `a single new ROM is added to its system and the walk is not run again`() = runBlocking {
        val old = touch("roms/snes/old.sfc").path
        val added = touch("roms/snes/new.sfc")
        val provider = PathProvider(
            LibraryEntryKind.CONSOLE_ROM, temp.root.path,
            listOf("snes" to listOf(rom(old)), "gba" to listOf(rom("/other/x.gba"))),
        ) { path -> listOf(PathIndexing("snes", temp.root.path, listOf(rom(path.path)), under = path.path)) }
        val (library, index, _) = walked(provider)
        val savesAfterWalk = index.saves

        val result = library.indexPaths(added = listOf(added))

        assertEquals(1, provider.walks)
        assertEquals(listOf(added.path), provider.asked)
        assertEquals(listOf(old, added.path, "/other/x.gba").sorted(), ids(index, provider))
        assertEquals(1, result.reindexed)
        assertEquals(0, result.dropped)
        assertEquals("only the changed part is written", savesAfterWalk + 1, index.saves)
    }

    @Test
    fun `a new PC game folder is added to its top-level folder and the folder's other games are kept`() = runBlocking {
        val kept = folderGame(File(temp.root, "Games/Old").path, "folder:CUSTOM_GAME_1")
        val newFolder = File(temp.root, "Games/New").also { it.mkdirs() }
        File(newFolder, "Game.exe").writeText("MZ")
        val provider = PathProvider(
            LibraryEntryKind.WINE_PROFILE, temp.root.path,
            listOf(File(temp.root, "Games").path to listOf(kept)),
        ) { path -> listOf(PathIndexing(File(temp.root, "Games").path, temp.root.path, listOf(folderGame(path.path)), under = path.path)) }
        val (library, index, _) = walked(provider)

        library.indexPaths(added = listOf(newFolder))

        assertEquals(1, provider.walks)
        assertEquals(listOf("folder:CUSTOM_GAME_1", "folder:CUSTOM_GAME_7"), ids(index, provider))
        assertTrue(index.slices[provider.indexKey]!!.entries().none { it.missing })
        assertEquals("the part keeps the stamp the index has, unknown here", 0L, index.slices[provider.indexKey]!!.segments.single().folderMtime ?: 0L)
    }

    @Test
    fun `a removed file leaves the index and its record, and nothing else changes`() = runBlocking {
        val keep = touch("roms/snes/keep.sfc").path
        val gone = File(temp.root, "roms/snes/gone.sfc").path
        val provider = PathProvider(LibraryEntryKind.CONSOLE_ROM, temp.root.path, listOf("snes" to listOf(rom(keep), rom(gone))))
        val (library, index, records) = walked(provider)

        val result = library.indexPaths(removed = listOf(File(gone)))

        assertEquals(listOf(keep), ids(index, provider))
        assertEquals(listOf(gone), records.deleted)
        assertEquals(1, result.dropped)
        assertEquals(1, provider.walks)
    }

    @Test
    fun `a removed folder takes every game under it and a path that still exists is looked at instead`() = runBlocking {
        val inside = File(temp.root, "Games/Pack").path
        val provider = PathProvider(
            LibraryEntryKind.WINE_PROFILE, temp.root.path,
            listOf(File(temp.root, "Games").path to listOf(folderGame("$inside/A", "folder:CUSTOM_GAME_1"), folderGame("$inside/B", "folder:CUSTOM_GAME_2"), folderGame(File(temp.root, "Games/Other").path, "folder:CUSTOM_GAME_3"))),
        )
        val (library, index, records) = walked(provider)

        library.indexPaths(removed = listOf(File(inside)))
        assertEquals(listOf("folder:CUSTOM_GAME_3"), ids(index, provider))
        assertEquals(listOf("folder:CUSTOM_GAME_1", "folder:CUSTOM_GAME_2"), records.deleted.sorted())

        // Reported removed but still there: not dropped, asked about as changed.
        val still = File(temp.root, "Games/Other").also { it.mkdirs() }
        library.indexPaths(removed = listOf(still))
        assertEquals(listOf("folder:CUSTOM_GAME_3"), ids(index, provider))
        assertEquals(still.path, provider.asked.last())
    }

    @Test
    fun `a store row that is not installed is never dropped by a removal`() = runBlocking {
        val folder = File(temp.root, "Games/GOG/Game").path
        val row = LibraryEntry(
            id = "gog:1", title = "Game", kind = LibraryEntryKind.WINE_PROFILE, systemId = "pc",
            pcInfo = PcInfo(installed = false, installPath = folder),
        )
        val provider = PathProvider(LibraryEntryKind.WINE_PROFILE, temp.root.path, listOf(ScanStep.WHOLE to listOf(row)))
        val (library, index, records) = walked(provider)

        library.indexPaths(removed = listOf(File(folder)))

        assertEquals(listOf("gog:1"), ids(index, provider))
        assertTrue(records.deleted.isEmpty())
    }

    @Test
    fun `a provider that has never walked is left alone`() = runBlocking {
        val walkedProvider = PathProvider(LibraryEntryKind.CONSOLE_ROM, temp.root.path, listOf("snes" to emptyList()))
        val fresh = object : LibraryProvider {
            override val kinds = setOf(LibraryEntryKind.RENPY)
            val asked = ArrayList<String>()
            override suspend fun scan(): List<LibraryEntry> = emptyList()
            override suspend fun launch(entry: LibraryEntry) {}
            override suspend fun indexPath(path: File): List<PathIndexing> {
                asked += path.path
                return listOf(PathIndexing("p", null, emptyList(), under = path.path))
            }
        }
        val index = FakeIndex()
        val library = Library(listOf(walkedProvider, fresh), index = index)
        library.scanKindsProgressive(setOf(LibraryEntryKind.CONSOLE_ROM)).toList()

        library.indexPaths(added = listOf(touch("roms/snes/a.sfc")))

        assertTrue("nothing has walked it, the first walk will find the file", fresh.asked.isEmpty())
        assertNull(index.slices[fresh.indexKey])
    }

    @Test
    fun `a file inside an indexed game stands for the game's folder`() = runBlocking {
        val game = File(temp.root, "Games/Pack/Game").also { it.mkdirs() }
        val data = touch("Games/Pack/Game/data/level1.bin")
        val provider = PathProvider(
            LibraryEntryKind.WINE_PROFILE, temp.root.path,
            listOf(File(temp.root, "Games").path to listOf(folderGame(game.path))),
        ) { path -> listOf(PathIndexing(File(temp.root, "Games").path, temp.root.path, listOf(folderGame(path.path).copy(title = "Re-read")), under = path.path)) }
        val (library, index, _) = walked(provider)

        library.indexPaths(changed = listOf(data))

        assertEquals("the game, not the file in its data, is what is looked at again", listOf(game.path), provider.asked)
        assertEquals("Re-read", index.slices[provider.indexKey]!!.entries().single().title)
    }

    @Test
    fun `a game that was under the path and is not in the answer is kept as missing`() = runBlocking {
        val folder = File(temp.root, "Games/Gone").also { it.mkdirs() }
        val provider = PathProvider(
            LibraryEntryKind.WINE_PROFILE, temp.root.path,
            listOf(File(temp.root, "Games").path to listOf(folderGame(folder.path))),
        ) { path -> listOf(PathIndexing(File(temp.root, "Games").path, temp.root.path, emptyList(), under = path.path)) }
        val (library, index, records) = walked(provider)

        library.indexPaths(changed = listOf(folder))

        val entry = index.slices[provider.indexKey]!!.entries().single()
        assertTrue(entry.missing)
        assertTrue("it is not a removal, so the record stays", records.deleted.isEmpty())
    }

    @Test
    fun `the provider that reads the others' inputs is asked first`() = runBlocking {
        val order = ArrayList<String>()
        fun recording(name: String, early: Boolean) = PathProvider(
            if (early) LibraryEntryKind.WINE_PROFILE else LibraryEntryKind.RENPY, temp.root.path, listOf("p" to emptyList()),
            indexesPathsEarly = early, indexKey = name,
        ) { order += name; emptyList() }
        val late = recording("late", early = false)
        val early = recording("early", early = true)
        val (library, _, _) = walked(late, early)

        library.indexPaths(added = listOf(touch("Games/Some/x.bin")))

        assertEquals(listOf("early", "late"), order)
    }

    @Test
    fun `mergePath replaces only what is under the path and merges a whole part like a walk`() {
        val a = folderGame("/g/Top/A", "a")
        val b = folderGame("/g/Top/B", "b")
        val slice = LibrarySlice(listOf(ScanStep.Segment("/g/Top", "/g", listOf(a, b), folderMtime = 55L)))

        val replaced = slice.mergePath(PathIndexing("/g/Top", "/g", listOf(a.copy(title = "A2")), under = "/g/Top/A"), folderMtime = 9L)
        assertEquals(listOf("A2", "B"), replaced.segments.single().entries.map { it.title })
        assertEquals("the part keeps the stamp it has", 55L, replaced.segments.single().folderMtime)

        val created = slice.mergePath(PathIndexing("/g/New", "/g", listOf(folderGame("/g/New/C", "c")), under = "/g/New/C"), folderMtime = 9L)
        assertEquals(2, created.segments.size)
        assertEquals(9L, created.segments[1].folderMtime)

        val whole = slice.mergePath(PathIndexing("/g/Top", "/g", listOf(a), under = null, folderMtime = 77L), folderMtime = 9L)
        assertEquals(listOf(false, true), whole.segments.single().entries.map { it.missing })
        assertEquals(77L, whole.segments.single().folderMtime)
    }

    @Test
    fun `withoutUnder and idsUnder match a folder by path boundary, not by prefix`() {
        val slice = LibrarySlice(listOf(ScanStep.Segment("k", "/g", listOf(folderGame("/g/Pack/A", "a"), folderGame("/g/Pack2/B", "b"), rom("/g/Pack")))))
        assertEquals(listOf("a", "/g/Pack"), slice.idsUnder("/g/Pack").sortedBy { it != "a" })
        assertEquals(listOf("b"), slice.withoutUnder("/g/Pack").entries().map { it.id })
        assertFalse(slice.withoutUnder("/g/Pack").entries().any { it.id == "a" })
    }
}

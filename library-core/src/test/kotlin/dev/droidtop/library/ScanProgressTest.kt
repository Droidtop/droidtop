package dev.droidtop.library

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The three pieces that replace the whole-provider timeout the rig's
 * build 523 failed on: the per-folder budget, the incremental publish, and
 * the one-line-per-folder log.
 */
class ScanProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    /** A real Ren'Py game: renpy/ plus game/ is the detector's precise evidence. */
    private fun renpyGame(path: String): File = File(temp.root, path).apply {
        mkdirs()
        File(this, "renpy").mkdirs()
        File(this, "game").mkdirs()
    }

    // --- the budget ------------------------------------------------------

    @Test
    fun `a budget expires only once its time has actually passed`() {
        var now = 1_000L
        val budget = ScanBudget.start(budgetMs = 50L, clock = { now })
        assertFalse(budget.expired)
        now = 1_049L
        assertFalse(budget.expired)
        now = 1_050L
        assertTrue(budget.expired)
        assertEquals(50L, budget.elapsedMs)
    }

    @Test
    fun `an unlimited budget never expires`() {
        val budget = ScanBudget.unlimited()
        assertFalse(budget.expired)
    }

    @Test
    fun `a budget names itself in the reason a skipped folder is logged with`() {
        var now = 0L
        val budget = ScanBudget.start(budgetMs = 20_000L, clock = { now })
        now = 20_001L
        assertTrue(budget.expired)
        assertTrue(budget.reason().contains("20000 ms budget"))
    }

    // --- the budget inside the engine walk -------------------------------

    @Test
    fun `an over-budget folder keeps the games below it, and says where it was slow`() {
        // The rig's `adult/RPGMaker` (build 540): the folder's OWN step --
        // its listing plus the subtree-reading detection rules asked of
        // it -- ran past 20 s over the shared folder, and the six games
        // inside it were dropped with it. A budget that drops a subtree
        // loses real games, so it now costs this folder its own evidence
        // and nothing else: the walk still goes below it, and every
        // folder below gets a budget of its own.
        val folder = File(temp.root, "RPGMaker").apply { mkdirs() }
        renpyGame("RPGMaker/Some Game")
        renpyGame("RPGMaker/Another Game")
        var now = 0L
        val budget = ScanBudget.start(budgetMs = 10L, clock = { now })
        now = 100L

        var handed = 0
        val scanned = GameEngineDetector.scanFolder(folder, emptyMap(), defs) {
            // Only the folder's own budget is exhausted; its children get
            // honest ones, exactly as a real walk hands them out.
            handed++
            if (handed == 1) budget else ScanBudget.unlimited()
        }

        assertEquals(
            listOf("Another Game", "Some Game"),
            scanned.games.map { it.displayFolder.name }.sorted(),
        )
        assertEquals(folder, scanned.stoppedAt)
        assertEquals(1, scanned.skipped.total)
        assertTrue(scanned.skipped.counts().keys.single().contains("budget"))
    }

    @Test
    fun `a folder inside its budget is walked whole and reports nothing stopped`() {
        renpyGame("adult/renpy/A Game")
        renpyGame("adult/renpy/Another Game")
        val folder = File(temp.root, "adult")

        val scanned = GameEngineDetector.scanFolder(folder, emptyMap(), defs, budget = { ScanBudget.unlimited() })

        assertEquals(
            listOf("A Game", "Another Game"),
            scanned.games.map { it.displayFolder.name }.sorted(),
        )
        assertNull(scanned.stoppedAt)
    }

    @Test
    fun `a root is walked as independent top-level folders, with the root's own skips counted`() {
        renpyGame("games/adult/renpy/A Game")
        renpyGame("games/Manual/Extracted/Another Game")
        File(temp.root, "games/.stfolder").mkdirs()
        File(temp.root, "games/Steam/steamapps/workshop/content/1/2").mkdirs()
        File(temp.root, "games/Steam/libraryfolder.vdf").writeText("x")
        renpyGame("games/Steam/steamapps/common/Store Game")
        val root = File(temp.root, "games")

        val top = GameEngineDetector.topLevelFolders(root, emptyMap())

        // Each unit of work is its own folder, so one slow one costs one.
        assertEquals(listOf("Manual", "Steam", "adult"), top.folders.map { it.name })
        assertEquals(mapOf("it is a hidden folder" to 1), top.skipped.counts())
        assertEquals(listOf(".stfolder"), top.skipped.folders("it is a hidden folder").map { it.name })

        // And every folder's games are found, the store one included
        // (SPEC 7g, yardstick item 5) while the workshop tree is not walked.
        val games = top.folders.flatMap {
            GameEngineDetector.scanFolder(it, emptyMap(), defs, budget = { ScanBudget.unlimited() }).games
        }
        assertEquals(
            listOf("A Game", "Another Game", "Store Game"),
            games.map { it.displayFolder.name }.sorted(),
        )
        val steamSkips = GameEngineDetector
            .scanFolder(File(root, "Steam"), emptyMap(), defs, budget = { ScanBudget.unlimited() })
            .skipped
            .counts()
        assertTrue(steamSkips.keys.toString(), steamSkips.keys.any { it.contains("Steam owns this tree") })
    }

    @Test
    fun `only the folder whose own step is slow is skipped, and its siblings keep their games`() {
        renpyGame("cat/A Game")
        renpyGame("cat/B Game")
        renpyGame("cat/slow/Hidden By Slowness")
        val folder = File(temp.root, "cat")

        // One budget per folder, in walk order: cat, A Game, B Game, slow.
        // Only the fourth expires, which is the whole point -- a big
        // healthy library is not pathology, and must not be truncated as
        // though it were (rig: `adult/` surfaced ONE game of hundreds when
        // the budget covered a subtree instead of a folder's own step).
        var folders = 0
        val scanned = GameEngineDetector.scanFolder(folder, emptyMap(), defs) {
            folders++
            if (folders == 4) {
                var tick = 0L
                ScanBudget.start(budgetMs = 5L, clock = { tick += 10L; tick })
            } else {
                ScanBudget.unlimited()
            }
        }

        assertEquals(listOf("A Game", "B Game"), scanned.games.map { it.displayFolder.name }.sorted())
        assertEquals("slow", scanned.stoppedAt?.name)
        assertTrue(scanned.skipped.counts().keys.single().contains("budget"))
    }

    @Test
    fun `the rom walk skips only the directory whose own listing is slow`() {
        for (name in listOf("a.iso", "b.iso")) File(temp.root, "ps2/$name").apply { parentFile.mkdirs(); writeText("x") }
        File(temp.root, "ps2/slow/c.iso").apply { parentFile.mkdirs(); writeText("x") }
        File(temp.root, "ps2/slow/deeper/d.iso").apply { parentFile.mkdirs(); writeText("x") }
        File(temp.root, "ps2/DLC/e.iso").apply { parentFile.mkdirs(); writeText("x") }

        // Directories are listed in name order: ps2, DLC (refused by name),
        // slow. The budget handed out for `slow` is the one that expires.
        var directories = 0
        val result = dev.droidtop.library.consoles.RomScanWalk.walk(File(temp.root, "ps2"), setOf("iso")) {
            directories++
            if (directories == 2) {
                var tick = 0L
                ScanBudget.start(budgetMs = 5L, clock = { tick += 10L; tick })
            } else {
                ScanBudget.unlimited()
            }
        }

        assertEquals(listOf("a.iso", "b.iso"), result.files.map { it.name })
        assertEquals("slow", result.stoppedAt?.name)
        val reasons = ScanSkips.of(result.skipped).counts()
        assertEquals(1, reasons.count { it.key.contains("add-on content") })
        assertEquals(1, reasons.count { it.key.contains("budget") })
    }

    // --- the log line ----------------------------------------------------

    @Test
    fun `a scan logs one line per folder, naming the folders each rule fired on`() {
        val base = File("/mnt/windows/BstSharedFolder/Ubisoft")
        val skips = ScanSkips()
        // The build-540 line a person could not act on: "2 x it is a
        // console system folder" without ever saying which two folders.
        skips.add(File(base, "Far Cry 5/data_final/pc"), CONSOLE)
        skips.add(File(base, "Far Cry New Dawn/data_final/pc"), CONSOLE)
        skips.add(File(base, ".gamenative"), "it is a hidden folder")

        val line = ScanLog.summary(
            label = "engine folder ${base.path}",
            games = 3,
            skipped = skips,
            durationMs = 1690,
            base = base,
        )

        assertEquals(
            "engine folder /mnt/windows/BstSharedFolder/Ubisoft: 3 games, 3 folders skipped " +
                "(2 x $CONSOLE (Far Cry 5/data_final/pc, Far Cry New Dawn/data_final/pc); " +
                "1 x it is a hidden folder (.gamenative)), 1690 ms",
            line,
        )
    }

    @Test
    fun `one rule firing on a thousand folders still costs one short line`() {
        val base = File("/games/Steam")
        val store = "Steam owns this tree -- its games are listed from steamapps/common"
        val skips = ScanSkips()
        repeat(1434) { skips.add(File(base, "steamapps/workshop/content/$it"), store) }

        val line = ScanLog.summary(label = "rom folder ${base.path}", games = 0, skipped = skips, durationMs = 812, base = base)

        assertTrue(line, line.startsWith("rom folder /games/Steam: 0 games, 1434 folders skipped (1434 x $store ("))
        assertTrue(line, line.contains("steamapps/workshop/content/0, "))
        assertTrue(line, line.contains("+1428 more"))
        assertEquals(1, line.lines().size)
    }

    @Test
    fun `a cut-short folder says so on its own line`() {
        val line = ScanLog.summary(
            label = "engine folder /games/Steam",
            games = 1,
            skipped = ScanSkips(),
            durationMs = 20_003,
            note = "read too slowly in /games/Steam/steamapps/common, so only its own evidence was dropped",
        )
        assertEquals(
            "engine folder /games/Steam: 1 game, 0 folders skipped, 20003 ms " +
                "-- read too slowly in /games/Steam/steamapps/common, so only its own evidence was dropped",
            line,
        )
    }

    @Test
    fun `skips are grouped by the rule that fired, and keep every folder it fired on`() {
        val hidden = "it is a hidden folder"
        val store = "Steam owns this tree"
        val skips = ScanSkips.of(
            listOf(
                File("/a/.stfolder") to hidden,
                File("/a/.gamenative") to hidden,
                File("/a/Steam/steamapps/workshop") to store,
            ),
        )

        assertEquals(mapOf(hidden to 2, store to 1), skips.counts())
        assertEquals(listOf(".stfolder", ".gamenative"), skips.folders(hidden).map { it.name })
        assertEquals(3, skips.total)
    }

    private val CONSOLE = "it is a console system folder, scanned for ROMs instead"

    // --- the incremental publish -----------------------------------------

    /** A provider that emits growing snapshots, optionally failing partway. */
    private class StreamingProvider(
        kind: LibraryEntryKind,
        private val batches: List<List<LibraryEntry>>,
        private val failAfterBatches: Int = -1,
    ) : LibraryProvider {
        override val kinds = setOf(kind)
        override suspend fun scan(): List<LibraryEntry> = batches.lastOrNull() ?: emptyList()
        override suspend fun launch(entry: LibraryEntry) = Unit
        override fun scanProgressive(): Flow<List<LibraryEntry>> = flow {
            batches.forEachIndexed { index, batch ->
                if (index == failAfterBatches) error("this folder blew up")
                emit(batch)
            }
        }
    }

    private fun entry(id: String, kind: LibraryEntryKind) = LibraryEntry(id = id, title = id, kind = kind)

    @Test
    fun `results are published as each folder arrives, not only at the end`() = runBlocking {
        val first = entry("a", LibraryEntryKind.RENPY)
        val second = entry("b", LibraryEntryKind.RENPY)
        val provider = StreamingProvider(
            LibraryEntryKind.RENPY,
            listOf(listOf(first), listOf(first, second)),
        )
        val library = Library(listOf(provider))

        val snapshots = library.scanKindsProgressive(setOf(LibraryEntryKind.RENPY)).toList()

        assertEquals(listOf(1, 2), snapshots.map { it.size })
        assertEquals(listOf(first, second), snapshots.last())
    }

    @Test
    fun `a folder that fails keeps every result the scan already published`() = runBlocking {
        val kept = entry("kept", LibraryEntryKind.RENPY)
        val other = entry("other", LibraryEntryKind.CONSOLE_ROM)
        val failing = StreamingProvider(
            LibraryEntryKind.RENPY,
            listOf(listOf(kept), listOf(kept, entry("never", LibraryEntryKind.RENPY))),
            failAfterBatches = 1,
        )
        val healthy = StreamingProvider(LibraryEntryKind.CONSOLE_ROM, listOf(listOf(other)))
        val library = Library(listOf(failing, healthy))

        val snapshots = library
            .scanKindsProgressive(setOf(LibraryEntryKind.RENPY, LibraryEntryKind.CONSOLE_ROM))
            .toList()

        // Nothing is discarded because something else failed: the library's
        // last snapshot holds the failing provider's partial results AND
        // the healthy provider's, which is what the 60-second whole-provider
        // timeout used to throw away (rig, build 523).
        val last = snapshots.last()
        assertTrue(last.map { it.id }.contains("kept"))
        assertTrue(last.map { it.id }.contains("other"))
        assertFalse(last.map { it.id }.contains("never"))
    }

    @Test
    fun `background rescan survives when its screen stops observing`() = runBlocking {
        val first = entry("first", LibraryEntryKind.RENPY)
        val second = entry("second", LibraryEntryKind.RENPY)
        val continueScan = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val provider = object : LibraryProvider {
            override val kinds = setOf(LibraryEntryKind.RENPY)
            override suspend fun scan() = emptyList<LibraryEntry>()
            override suspend fun launch(entry: LibraryEntry) = Unit
            override fun rescanProgressive(): Flow<List<LibraryEntry>> = flow {
                starts.incrementAndGet()
                emit(listOf(first))
                continueScan.await()
                emit(listOf(first, second))
            }
        }
        val library = Library(listOf(provider))
        val state = library.backgroundScanState(provider.kinds)

        library.scanInBackground(provider.kinds, rescan = true)
        withTimeout(5_000) {
            while (state.value != listOf(first)) delay(1)
        }

        // Recreating the screen replays the same trigger, but must attach to
        // the in-flight process job instead of restarting its folder walk.
        repeat(100) { library.scanInBackground(provider.kinds, rescan = true) }

        // No StateFlow collector remains here: this models navigating away
        // or destroying/recreating the Activity while the provider is busy.
        continueScan.complete(Unit)
        withTimeout(5_000) {
            while (state.value != listOf(first, second)) delay(1)
        }

        assertEquals(listOf(first, second), state.value)
        assertEquals(1, starts.get())
    }
}

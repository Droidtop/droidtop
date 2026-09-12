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
    fun `an over-budget folder stops being walked and says where`() {
        val folder = File(temp.root, "Steam").apply { mkdirs() }
        renpyGame("Steam/steamapps/common/Some Game")
        var now = 0L
        val budget = ScanBudget.start(budgetMs = 10L, clock = { now })
        now = 100L

        val scanned = GameEngineDetector.scanFolder(folder, emptyMap(), defs, budget = { budget })

        assertEquals(emptyList<DetectedGame>(), scanned.games)
        assertEquals(folder, scanned.stoppedAt)
        assertEquals(1, scanned.skippedByReason.values.sum())
        assertTrue(scanned.skippedByReason.keys.single().contains("budget"))
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
        assertEquals(mapOf("it is a hidden folder" to 1), top.skippedByReason)

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
            .skippedByReason
        assertTrue(steamSkips.keys.toString(), steamSkips.keys.any { it.contains("Steam owns this tree") })
    }

    @Test
    fun `only the folder whose own step is slow is skipped; its siblings keep their games`() {
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
        assertTrue(scanned.skippedByReason.keys.single().contains("budget"))
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
        val reasons = ScanLog.countByReason(result.skipped)
        assertEquals(1, reasons.count { it.key.contains("add-on content") })
        assertEquals(1, reasons.count { it.key.contains("budget") })
    }

    // --- the log line ----------------------------------------------------

    @Test
    fun `a scan logs one line per folder with counts, not one line per skip`() {
        val line = ScanLog.summary(
            label = "rom folder /mnt/windows/BstSharedFolder/Steam",
            games = 0,
            skippedByReason = mapOf(
                "Steam owns this tree -- its games are listed from steamapps/common" to 1434,
                "it is a hidden folder" to 3,
            ),
            durationMs = 812,
        )
        assertEquals(
            "rom folder /mnt/windows/BstSharedFolder/Steam: 0 games, 1437 folders skipped " +
                "(1434 x Steam owns this tree -- its games are listed from steamapps/common; " +
                "3 x it is a hidden folder), 812 ms",
            line,
        )
    }

    @Test
    fun `a cut-short folder says so on its own line`() {
        val line = ScanLog.summary(
            label = "engine folder /games/Steam",
            games = 1,
            skippedByReason = emptyMap(),
            durationMs = 20_003,
            note = "stopped in /games/Steam/steamapps/common",
        )
        assertEquals(
            "engine folder /games/Steam: 1 game, 0 folders skipped, 20003 ms " +
                "-- stopped in /games/Steam/steamapps/common",
            line,
        )
    }

    @Test
    fun `skips are counted by the rule that fired, not by folder`() {
        val hidden = "it is a hidden folder"
        val store = "Steam owns this tree"
        val counted = ScanLog.countByReason(
            listOf(
                File("/a/.stfolder") to hidden,
                File("/a/.gamenative") to hidden,
                File("/a/Steam/steamapps/workshop") to store,
            ),
        )
        assertEquals(mapOf(hidden to 2, store to 1), counted)
    }

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
}

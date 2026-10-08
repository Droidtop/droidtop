package dev.droidtop.library.settings

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "These files changed" (docs/SPEC.md 7g, "Targeted indexing", Droidtop/tracker#354): a report is written down
 * before anything else, indexed in batches, forgotten only once indexed, and still there after a restart.
 */
class LibraryPathsTest {
    private val dir: File = Files.createTempDirectory("library-paths").toFile()
    private val file = File(dir, "library/pending_paths.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun reporter() = PathReporter(PendingPathChanges(file), scope)

    private fun added(vararg paths: String) = PathChange(added = paths.toList())

    @Test
    fun `a report is indexed once and forgotten`() = runBlocking {
        val seen = ArrayList<PathChange>()
        val reporter = reporter()
        reporter.install { seen += it }.join()

        reporter.report(added("/games/snes/a.sfc"))!!.join()

        assertEquals(listOf(added("/games/snes/a.sfc")), seen)
        assertTrue(PendingPathChanges(file).all().isEmpty())
        assertFalse("the file goes with the last report", file.exists())
    }

    @Test
    fun `reports made before the library is ready are indexed when it installs, in one batch`() = runBlocking {
        val early = reporter()
        early.report(added("/games/snes/a.sfc"))!!.join()
        early.report(PathChange(removed = listOf("/games/snes/old.sfc"), changed = listOf("/games/snes/b.sfc")))!!.join()
        assertEquals("nothing could index them yet", 2, PendingPathChanges(file).all().size)

        val seen = ArrayList<PathChange>()
        early.install { seen += it }.join()

        assertEquals(
            listOf(PathChange(added = listOf("/games/snes/a.sfc"), removed = listOf("/games/snes/old.sfc"), changed = listOf("/games/snes/b.sfc"))),
            seen,
        )
        assertTrue(PendingPathChanges(file).all().isEmpty())
    }

    @Test
    fun `a report left by a process that ended is indexed by the next one`() = runBlocking {
        // The first process wrote the report and died before its handler returned.
        val dying = reporter()
        dying.report(added("/games/Platformer"))!!.join()
        assertEquals(1, PendingPathChanges(file).all().size)

        // The next process starts with the same file and a handler that works.
        val seen = ArrayList<PathChange>()
        reporter().install { seen += it }.join()

        assertEquals(listOf(added("/games/Platformer")), seen)
        assertTrue(PendingPathChanges(file).all().isEmpty())
    }

    @Test
    fun `a handler that fails keeps the report for the next try`() = runBlocking {
        val reporter = reporter()
        var fail = true
        val seen = ArrayList<PathChange>()
        reporter.install { change ->
            if (fail) error("the library is not ready")
            seen += change
        }.join()

        reporter.report(added("/games/a.sfc"))!!.join()
        assertTrue(seen.isEmpty())
        assertEquals(1, PendingPathChanges(file).all().size)

        fail = false
        reporter.report(added("/games/b.sfc"))!!.join()
        assertEquals(listOf(added("/games/a.sfc", "/games/b.sfc")), seen)
        assertTrue(PendingPathChanges(file).all().isEmpty())
    }

    @Test
    fun `an empty report is nothing`() {
        assertNull(reporter().report(PathChange()))
        assertFalse(file.exists())
    }

    @Test
    fun `reports merge without repeating a path`() {
        val merged = added("/a", "/b") + PathChange(added = listOf("/b", "/c"), removed = listOf("/d"))
        assertEquals(listOf("/a", "/b", "/c"), merged.added)
        assertEquals(listOf("/d"), merged.removed)
        assertTrue(merged.changed.isEmpty())
    }

    @Test
    fun `a queue that only grows keeps the newest reports`() {
        val pending = PendingPathChanges(file)
        repeat(PendingPathChanges.MAX_PENDING + 5) { pending.add(added("/games/$it")) }
        val all = pending.all()
        assertEquals(PendingPathChanges.MAX_PENDING, all.size)
        assertEquals("/games/5", all.first().added.single())
    }

    @Test
    fun `only paths inside a game folder may be reported`() {
        val roots = listOf("/storage/games", "/sdcard/ROMs/")
        assertTrue(LibraryPaths.outside(listOf("/storage/games/snes/a.sfc", "/sdcard/ROMs/x/y"), roots).isEmpty())
        assertEquals(
            listOf("/storage/games", "/storage/games2/a", "/etc/passwd", "/storage/games/../secret", "relative/a", "/storage/games/./a"),
            LibraryPaths.outside(
                listOf("/storage/games", "/storage/games2/a", "/etc/passwd", "/storage/games/../secret", "relative/a", "/storage/games/./a"),
                roots,
            ),
        )
        assertEquals("with no game folder nothing is inside one", listOf("/a/b"), LibraryPaths.outside(listOf("/a/b"), emptyList()))
    }
}

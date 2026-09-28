package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When droidtop first recorded a game (docs/SPEC.md 7i, the library views'
 * "Added" sort): the merge stamps a never-seen id once and never rewrites
 * it — not when the game is walked again, not while it is missing, and not
 * when the folder it used to live in finally reports it gone.
 */
class LibrarySliceAddedTest {

    private fun game(path: String) = LibraryEntry(id = path, title = path.substringAfterLast('/'), kind = LibraryEntryKind.RENPY)

    @Test
    fun `a game the library has never seen is stamped by the walk that finds it`() {
        val slice = LibrarySlice().merge(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(game("/g/a"))))

        val stamped = slice.entries().single()
        assertNotNull(stamped.addedEpochMs)
    }

    @Test
    fun `walking the same game again keeps the stamp it already has`() {
        val first = LibrarySlice().merge(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(game("/g/a"))))
        val stamp = first.entries().single().addedEpochMs
        // A later walk finds the same folder holding the same game; the
        // entry it hands back carries no stamp of its own (a walk does not
        // know one), so the merge must not mistake that for "new".
        assertNotNull(stamp)

        val second = first.merge(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(game("/g/a"))))

        assertEquals(stamp, second.entries().single().addedEpochMs)
    }

    @Test
    fun `a missing game that comes back keeps the stamp from when it first arrived`() {
        val withGame = LibrarySlice().merge(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(game("/g/a"))))
        val stamp = withGame.entries().single().addedEpochMs
        val emptied = withGame.merge(ScanStep.Segment(key = "/g", root = "/g", entries = emptyList()))
        assertTrue(emptied.entries().single().missing)
        val back = emptied.merge(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(game("/g/a"))))

        assertEquals(stamp, back.entries().single().addedEpochMs)
        assertEquals(false, back.entries().single().missing)
    }

    @Test
    fun `a game known to another part of the same slice is not new`() {
        val overlapping = LibrarySlice()
            .merge(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(game("/g/a"))))
            .merge(ScanStep.Segment(key = "/g2", root = "/g2", entries = listOf(game("/g/a"))))

        val stamps = overlapping.entries().map { it.addedEpochMs }.distinct()
        assertEquals(1, stamps.size)
    }
}

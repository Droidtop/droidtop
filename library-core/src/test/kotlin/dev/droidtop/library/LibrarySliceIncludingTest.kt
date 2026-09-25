package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** A rebuild from the records never ends on fewer games than were shown (docs/SPEC.md 7g). */
class LibrarySliceIncludingTest {

    private fun game(path: String) = LibraryEntry(id = path, title = path.substringAfterLast('/'), kind = LibraryEntryKind.RENPY)

    @Test
    fun `a shown game with no record is kept in the part it was shown in`() {
        val a = game("/g/a")
        val b = game("/g/b")
        val c = game("/h/c")
        val rebuilt = LibrarySlice(listOf(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(a))))
        val shown = LibrarySlice(
            listOf(
                ScanStep.Segment(key = "/g", root = "/g", entries = listOf(a, b)),
                ScanStep.Segment(key = "/h", root = "/h", entries = listOf(c)),
            ),
        )

        val union = rebuilt.including(shown)

        assertEquals(listOf(a, b, c), union.entries())
        assertEquals(listOf("/g", "/h"), union.segments.map { it.key })
    }

    @Test
    fun `a game the records have wins over the shown copy`() {
        val fromRecord = game("/g/a").copy(title = "From the record")
        val onScreen = game("/g/a")
        val rebuilt = LibrarySlice(listOf(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(fromRecord))))
        val shown = LibrarySlice(listOf(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(onScreen))))

        assertEquals(listOf(fromRecord), rebuilt.including(shown).entries())
    }

    @Test
    fun `nothing shown leaves the rebuilt slice as it is`() {
        val rebuilt = LibrarySlice(listOf(ScanStep.Segment(key = "/g", root = "/g", entries = listOf(game("/g/a")))))

        assertEquals(rebuilt, rebuilt.including(LibrarySlice()))
    }
}

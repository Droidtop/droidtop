package dev.droidtop.library.f95checker

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch-list import's matching rules (docs/SPEC.md 7g, "The watch
 * list, imported once"), against invented games in the shapes the real
 * watch list and a real library produce. Every game name here is fake, the
 * way LibraryTest's "StarHarbor" is.
 */
class F95CheckerImportTest {

    private fun folder(path: String, thread: Long? = null) = LibraryEntry(
        id = path,
        title = path.substringAfterLast('/'),
        kind = LibraryEntryKind.RENPY,
        f95Thread = thread,
    )

    private fun watched(threadId: Long, name: String, version: String? = null, installed: String? = null) =
        F95CheckerImport.WatchedGame(threadId, name, version, installed)

    private fun match(vararg watch: F95CheckerImport.WatchedGame, vararg entries: LibraryEntry) =
        F95CheckerImport.match(watch.toList(), LibraryGrouping.group(entries.toList()))

    @Test
    fun `a name and version that match a game corroborate the pairing`() {
        val matches = match(
            watched(12345, "Star Harbor", version = "v0.3"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )

        val link = matches.offered.single()
        assertEquals(12345L, link.watch.threadId)
        assertEquals("StarHarbor", link.game.game.name)
        assertTrue(link.corroborated)
        assertFalse(link.ambiguous)
        assertTrue(link.recommended)
    }

    @Test
    fun `punctuation and case do not stop a name match`() {
        val matches = match(
            watched(1, "MOON..GARDEN", version = "1.0"),
            folder("/games/renpy/moon_garden-1.0-pc"),
        )

        val link = matches.offered.single()
        assertTrue(link.corroborated)
        assertTrue(link.recommended)
    }

    @Test
    fun `a version none of the game's folders has does not corroborate`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.9"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )

        val link = matches.offered.single()
        assertFalse(link.corroborated)
        assertFalse(link.recommended)
    }

    @Test
    fun `the version the user marked installed corroborates where the thread's newest does not`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.9", installed = "0.3"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )

        assertTrue(matches.offered.single().corroborated)
        assertTrue(matches.offered.single().recommended)
    }

    @Test
    fun `a row with no version still names the game but corroborates nothing`() {
        val matches = match(
            watched(1, "Star Harbor"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )

        val link = matches.offered.single()
        assertFalse(link.corroborated)
        assertFalse(link.recommended)
    }

    @Test
    fun `a game whose folders name no version cannot be corroborated either`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.9"),
            folder("/games/renpy/StarHarbor"),
        )

        assertFalse(matches.offered.single().corroborated)
        assertFalse(matches.offered.single().recommended)
    }

    @Test
    fun `a game already linked to the row's own thread is not offered`() {
        val matches = match(
            watched(12345, "Star Harbor", version = "v0.3"),
            folder("/games/renpy/StarHarbor-0.3-pc", thread = 12345),
        )

        assertTrue(matches.offered.isEmpty())
        assertEquals(1, matches.alreadyLinked)
        assertEquals(0, matches.unmatched)
    }

    @Test
    fun `a game linked to another thread is offered for the person to decide`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.3"),
            folder("/games/renpy/StarHarbor-0.3-pc", thread = 999),
        )

        val link = matches.offered.single()
        assertFalse(link.recommended)
        assertTrue(F95CheckerImport.linkLine(link).contains("thread 999"))
    }

    @Test
    fun `two watch rows sharing a name are both offered and neither is recommended`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.3"),
            watched(2, "Star Harbor", version = "v0.3"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )

        assertEquals(listOf(1L, 2L), matches.offered.map { it.watch.threadId })
        assertTrue(matches.offered.all { it.ambiguous })
        assertTrue(matches.offered.none { it.recommended })
    }

    @Test
    fun `one row naming two games is offered for both, recommended for neither`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.3"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
            LibraryEntry(id = "steam:70", title = "Star Harbor", kind = LibraryEntryKind.WINE_PROFILE),
        )

        assertEquals(2, matches.offered.size)
        assertTrue(matches.offered.all { it.ambiguous })
        assertTrue(matches.offered.none { it.recommended })
    }

    @Test
    fun `a merely similar name never matches`() {
        val matches = match(
            watched(1, "Star Harbour"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )

        assertTrue(matches.offered.isEmpty())
        assertEquals(1, matches.unmatched)
    }

    @Test
    fun `a row that names no game here is counted unmatched`() {
        val matches = match(
            watched(1, "Moon Garden", version = "v0.2"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )

        assertTrue(matches.offered.isEmpty())
        assertEquals(1, matches.unmatched)
    }

    @Test
    fun `the row says the game's own spelling and both sides' versions`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.9", installed = "0.3"),
            folder("/games/renpy/StarHarbor-0.3-pc"),
        )
        val line = F95CheckerImport.linkLine(matches.offered.single())

        assertTrue(line.contains("F95Checker calls it \"Star Harbor\""))
        assertTrue(line.contains("v0.9"))
        assertTrue(line.contains("0.3 installed there"))
        assertTrue(line.contains("This game's folders hold 0.3"))
    }

    @Test
    fun `a custom row names no thread and a nameless row matches nothing`() {
        assertNull(F95CheckerImport.watchRow(-1, custom = 1, name = "Star Harbor", version = null, installed = null))
        assertNull(F95CheckerImport.watchRow(5, custom = 1, name = "Star Harbor", version = null, installed = null))
        assertNull(F95CheckerImport.watchRow(5, custom = 0, name = "   ", version = null, installed = null))
        assertEquals(
            F95CheckerImport.WatchedGame(5, "Star Harbor", null, null),
            F95CheckerImport.watchRow(5, custom = 0, name = " Star Harbor ", version = "Unchecked", installed = ""),
        )
    }

    @Test
    fun `the versions that say nothing are read as none`() {
        assertNull(F95CheckerImport.versionOf(null))
        assertNull(F95CheckerImport.versionOf("  "))
        assertNull(F95CheckerImport.versionOf("Unchecked"))
        assertNull(F95CheckerImport.versionOf("N/A"))
        assertEquals("v0.9.5", F95CheckerImport.versionOf(" v0.9.5 "))
    }

    @Test
    fun `a name that matches a game with several versions corroborates on any of them`() {
        val matches = match(
            watched(1, "Star Harbor", version = "v0.1"),
            folder("/games/renpy/StarHarbor-0.1-pc"),
            folder("/games/other/StarHarbor-0.4-pc"),
        )

        assertEquals(setOf("/games/renpy/StarHarbor-0.1-pc", "/games/other/StarHarbor-0.4-pc"), matches.offered.single().game.entriesByPath.keys)
        assertTrue(matches.offered.single().corroborated)
    }
}

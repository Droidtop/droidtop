package dev.droidtop.library.computers

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What makes the library cycle sync a moment after a change (docs/SPEC.md 7o, "When the library syncs"). */
class ComputerLibraryCycleTest {
    private val celeste = LibraryEntry(id = "/games/Celeste", title = "Celeste", kind = LibraryEntryKind.UNITY)
    private val other = LibraryEntry(id = "/games/Other", title = "Other", kind = LibraryEntryKind.UNITY)

    @Test
    fun `the same games and marks give the same fingerprint`() {
        assertEquals(
            ComputerLibraryCycle.fingerprint(listOf(celeste, other)),
            ComputerLibraryCycle.fingerprint(listOf(celeste.copy(), other.copy())),
        )
    }

    @Test
    fun `a favourite, a session played or an install changes it`() {
        val before = ComputerLibraryCycle.fingerprint(listOf(celeste, other))
        assertNotEquals(before, ComputerLibraryCycle.fingerprint(listOf(celeste.copy(favorite = true), other)))
        assertNotEquals(before, ComputerLibraryCycle.fingerprint(listOf(celeste.copy(playtimeSeconds = 60), other)))
        assertNotEquals(before, ComputerLibraryCycle.fingerprint(listOf(celeste, other, LibraryEntry(id = "/games/New", title = "New", kind = LibraryEntryKind.RENPY))))
    }
}

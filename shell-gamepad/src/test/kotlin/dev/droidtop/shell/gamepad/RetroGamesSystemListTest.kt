package dev.droidtop.shell.gamepad

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RetroGamesSystemListTest {
    @Test
    fun `Retro Games system list omits pc and windows`() {
        val entries = listOf(
            entry("nes-game", "nes"),
            entry("pc-game", "pc"),
            entry("windows-game", "windows"),
        )

        assertEquals(listOf("nes"), retroGamesSystemIds(entries))
    }

    @Test
    fun `the system view starts on the system the person came from, by id`() {
        val keys = listOf("collection:all", "system:gb", "system:snes")
        assertEquals(2, restoredFocusIndex(keys, "system:snes"))
        // A rescan that adds a system ahead of it moves the index, not the system.
        assertEquals(3, restoredFocusIndex(listOf("collection:all", "system:atari2600", "system:gb", "system:snes"), "system:snes"))
    }

    @Test
    fun `the system view starts on the first card with nothing to return to`() {
        val keys = listOf("collection:all", "system:gb")
        assertEquals(0, restoredFocusIndex(keys, null))
        assertEquals(0, restoredFocusIndex(keys, "system:gone"))
    }

    private fun entry(id: String, systemId: String) = LibraryEntry(
        id = id,
        title = id,
        kind = LibraryEntryKind.CONSOLE_ROM,
        systemId = systemId,
    )
}

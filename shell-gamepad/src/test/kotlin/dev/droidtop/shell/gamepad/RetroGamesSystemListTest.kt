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

    private fun entry(id: String, systemId: String) = LibraryEntry(
        id = id,
        title = id,
        kind = LibraryEntryKind.CONSOLE_ROM,
        systemId = systemId,
    )
}

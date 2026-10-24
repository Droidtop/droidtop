package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** A on a game card in Gaming opens it, never launches it (docs/SPEC.md 7i; owner, 2026-10-24, Droidtop/tracker#641). */
class CardPressTest {
    @Test
    fun `A opens a PC game's page and a Retro game's details, and only an app opens itself`() {
        val pc = LibraryEntry(id = "folder:GAME", title = "Game", kind = LibraryEntryKind.RENPY)
        val retro = LibraryEntry(id = "/roms/snes/Game.sfc", title = "Game", kind = LibraryEntryKind.CONSOLE_ROM, systemId = "snes")
        assertEquals(CardPress.PAGE, cardPress(pc))
        assertEquals(CardPress.DETAILS, cardPress(retro))
    }
}

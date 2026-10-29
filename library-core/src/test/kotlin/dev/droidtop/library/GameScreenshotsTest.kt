package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A game's own screenshot folder name (Droidtop/tracker#596): one per game id, and safe on any file system. */
class GameScreenshotsTest {
    @Test
    fun `a game id becomes a stable, file-safe folder name`() {
        val rom = GameScreenshots.keyOf("/storage/1234-5678/Roms/snes/Super Metroid (USA).sfc")
        assertEquals(24, rom.length)
        assertEquals(rom, GameScreenshots.keyOf("/storage/1234-5678/Roms/snes/Super Metroid (USA).sfc"))
        assertEquals(true, rom.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(rom, GameScreenshots.keyOf("steam:620"))
    }
}

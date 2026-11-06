package dev.droidtop.library.imports

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reading Pegasus's collections and favourites for an import (Droidtop/tracker#724). */
class PegasusImportTest {
    @Test
    fun `collections gather their own files and their games' files`() {
        val text = """
            # My SNES set
            collection: Super Nintendo
            shortname: snes
            files:
              Chrono Trigger.sfc
              Earthbound.sfc

            game: Super Metroid
            file: ./Super Metroid.sfc
            developer: Nintendo

            collection: Favourite RPGs
            files:
              Chrono Trigger.sfc
        """.trimIndent()
        assertEquals(
            listOf(
                PegasusImport.Collection("Super Nintendo", listOf("Chrono Trigger.sfc", "Earthbound.sfc", "./Super Metroid.sfc")),
                PegasusImport.Collection("Favourite RPGs", listOf("Chrono Trigger.sfc")),
            ),
            PegasusImport.parseMetadata(text),
        )
    }

    @Test
    fun `files match by path, by relative path, then by a unique file name`() {
        val paths = listOf("/sd/roms/snes/Chrono Trigger.sfc", "/sd/roms/snes/Super Metroid.sfc", "/sd/a/Tetris.gb", "/sd/b/Tetris.gb")
        val match = PegasusImport.match(listOf("snes/Chrono Trigger.sfc", "./Super Metroid.sfc", "Tetris.gb", "Missing.sfc", "/sd/a/Tetris.gb"), paths)
        assertEquals(listOf("/sd/roms/snes/Chrono Trigger.sfc", "/sd/roms/snes/Super Metroid.sfc", "/sd/a/Tetris.gb"), match.ids)
        // Tetris.gb alone names two entries, so it is not guessed; the absolute path names one.
        assertEquals(listOf("Tetris.gb", "Missing.sfc"), match.unmatched)
    }

    @Test
    fun `favourites are one file per line, and the report says what was found`() {
        assertEquals(listOf("/sd/a.sfc", "/sd/b.sfc"), PegasusImport.parseFavorites("/sd/a.sfc\n\n/sd/b.sfc\n/sd/a.sfc\n"))
        assertEquals("Favourites: 2 of 3 games found; 1 not in your library", PegasusImport.reportLine(2, 3, "Favourites"))
        assertEquals("Favourites: 3 of 3 games found", PegasusImport.reportLine(3, 3, "Favourites"))
    }
}

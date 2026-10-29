package dev.droidtop.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which games folder holds a file, by whole path segments (Droidtop/tracker#763). */
class GamesRootsRootOfTest {
    private val games = File("/storage/emulated/0/Games")
    private val games2 = File("/storage/emulated/0/Games2")
    private val nested = File("/storage/emulated/0/Games/psx")

    @Test
    fun `a folder whose name only starts the same does not hold the file`() {
        assertEquals(games2, GamesRoots.rootOf(listOf(games, games2), File("/storage/emulated/0/Games2/snes/mario.sfc")))
        assertNull(GamesRoots.rootOf(listOf(games), File("/storage/emulated/0/Games2/snes/mario.sfc")))
    }

    @Test
    fun `the most specific folder wins, and a folder holds itself`() {
        assertEquals(nested, GamesRoots.rootOf(listOf(games, nested), File("/storage/emulated/0/Games/psx/ff7.chd")))
        assertEquals(games, GamesRoots.rootOf(listOf(games), File("/storage/emulated/0/Games/")))
    }
}

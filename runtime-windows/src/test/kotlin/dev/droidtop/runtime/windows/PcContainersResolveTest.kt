package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcContainersResolveTest {
    private val ids = listOf("1", "STEAM_440", "GOG_42")

    @Test
    fun `a chosen prefix that exists wins over the game's own and the shared one`() {
        assertEquals("GOG_42", PcContainers.resolveId(chosen = "GOG_42", ownId = "STEAM_440", existing = ids, shared = "1"))
        // The way back: the game's own prefix exists, but the person chose the shared one.
        assertEquals("1", PcContainers.resolveId(chosen = "1", ownId = "STEAM_440", existing = ids, shared = "1"))
    }

    @Test
    fun `with no choice, or a chosen prefix since removed, the rule before choices applies`() {
        assertEquals("STEAM_440", PcContainers.resolveId(chosen = null, ownId = "STEAM_440", existing = ids, shared = "1"))
        assertEquals("STEAM_440", PcContainers.resolveId(chosen = "GONE", ownId = "STEAM_440", existing = ids, shared = "1"))
        assertEquals("1", PcContainers.resolveId(chosen = null, ownId = "STEAM_7", existing = ids, shared = "1"))
        assertEquals("1", PcContainers.resolveId(chosen = null, ownId = null, existing = ids, shared = "1"))
    }

    @Test
    fun `a game with no prefix of its own never lands in another game's, whatever is listed first`() {
        // Droidtop/tracker#928: one game's own prefix sorted first, and every shared-prefix game launched in it.
        val existing = listOf("CUSTOM_GAME_5", "STEAM_1")
        assertEquals("STEAM_1", PcContainers.resolveId(chosen = null, ownId = null, existing = existing, shared = "STEAM_1"))
        assertEquals("STEAM_1", PcContainers.resolveId(chosen = null, ownId = "STEAM_99", existing = existing, shared = "STEAM_1"))
    }

    @Test
    fun `without the shared prefix there is none, which is the Set up Windows games step`() {
        assertNull(PcContainers.resolveId(chosen = null, ownId = null, existing = listOf("STEAM_440", "GOG_42"), shared = null))
        assertNull(PcContainers.resolveId(chosen = "1", ownId = "STEAM_440", existing = emptyList(), shared = null))
    }

    @Test
    fun `the shared prefix is the one setup made, by its id or else by its name, never a game's own`() {
        assertEquals("1", PcContainers.sharedId(listOf("CUSTOM_GAME_5" to "Stardew Valley", "1" to "droidtop")))
        assertEquals("STEAM_1", PcContainers.sharedId(listOf("CUSTOM_GAME_5" to "Stardew Valley", "STEAM_1" to "droidtop")))
        assertNull(PcContainers.sharedId(listOf("CUSTOM_GAME_5" to "Stardew Valley", "STEAM_440" to "Kingdom Two Crowns")))
    }
}

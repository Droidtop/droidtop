package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcContainersResolveTest {
    private val ids = listOf("1", "STEAM_440", "GOG_42")

    @Test
    fun `a chosen prefix that exists wins over the game's own and the shared one`() {
        assertEquals("GOG_42", PcContainers.resolveId(chosen = "GOG_42", ownId = "STEAM_440", existing = ids))
        // The way back: the game's own prefix exists, but the person chose the shared one.
        assertEquals("1", PcContainers.resolveId(chosen = "1", ownId = "STEAM_440", existing = ids))
    }

    @Test
    fun `with no choice, or a chosen prefix since removed, the rule before choices applies`() {
        assertEquals("STEAM_440", PcContainers.resolveId(chosen = null, ownId = "STEAM_440", existing = ids))
        assertEquals("STEAM_440", PcContainers.resolveId(chosen = "GONE", ownId = "STEAM_440", existing = ids))
        assertEquals("1", PcContainers.resolveId(chosen = null, ownId = "STEAM_7", existing = ids))
        assertEquals("1", PcContainers.resolveId(chosen = null, ownId = null, existing = ids))
    }

    @Test
    fun `without the shared prefix the first one is taken, and none means none`() {
        assertEquals("STEAM_440", PcContainers.resolveId(chosen = null, ownId = null, existing = listOf("STEAM_440", "GOG_42")))
        assertNull(PcContainers.resolveId(chosen = "1", ownId = "STEAM_440", existing = emptyList()))
    }
}

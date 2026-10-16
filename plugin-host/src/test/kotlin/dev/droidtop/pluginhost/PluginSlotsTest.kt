package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which plugin processes are one plugin's for their whole life (docs/plugin-api.md 5.6, Droidtop/tracker#53). */
class PluginSlotsTest {
    @Test
    fun `open processes and contained slots below API 29 serve one plugin and end with it`() {
        assertTrue(PluginSlots.single(PluginTier.OPEN, perInstance = false))
        assertTrue("a contained slot below API 29: its filter and broker are the first plugin's", PluginSlots.single(PluginTier.CONTAINED, perInstance = false))
        assertTrue("a per-instance contained process is its plugin's by construction", !PluginSlots.single(PluginTier.CONTAINED, perInstance = true))
        assertFalse(PluginSlots.single(PluginTier.FULL_TRUST, perInstance = false))
    }

    @Test
    fun `a fresh process goes to the slot ended longest ago`() {
        val ended = mapOf("contained-slot0" to 300L, "contained-slot1" to 100L)
        assertEquals("contained-slot2", PluginSlots.freshest(listOf("contained-slot0", "contained-slot1", "contained-slot2"), ended))
        assertEquals("contained-slot1", PluginSlots.freshest(listOf("contained-slot0", "contained-slot1"), ended))
        assertNull(PluginSlots.freshest(emptyList(), ended))
    }
}

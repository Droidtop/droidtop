package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Not now for the session, and the last refusal a page names (Droidtop/tracker#518). */
class PluginRefusalsTest {
    @Test
    fun `Not now lasts until the person chooses on the Permissions page`() {
        PluginRefusals.rememberNotNow("refusals.a", "web.session")
        assertTrue(PluginRefusals.isNotNow("refusals.a", "web.session"))
        assertFalse(PluginRefusals.isNotNow("refusals.a", "vault.own"))
        assertFalse(PluginRefusals.isNotNow("refusals.b", "web.session"))
        PluginRefusals.forgetNotNow("refusals.a", "web.session")
        assertFalse(PluginRefusals.isNotNow("refusals.a", "web.session"))
    }

    @Test
    fun `a page names only a refusal made during its own call`() {
        PluginRefusals.noteRefused("refusals.c", "web.session", atMs = 1_000L)
        assertEquals("web.session", PluginRefusals.refusedSince("refusals.c", 900L))
        assertNull(PluginRefusals.refusedSince("refusals.c", 1_001L))
        assertNull(PluginRefusals.refusedSince("refusals.d", 0L))
    }
}

package dev.droidtop.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Plugins list's Show filter (Droidtop/tracker#513, #792). */
class PluginListFilterTest {
    @Test
    fun `a plugin that wants new access and has a newer version is under Updates`() {
        // On the console F95zone read "Wants new access" and its update showed nowhere on the list (Droidtop/tracker#792).
        assertTrue(PluginListFilter.UPDATES.keeps("Wants new access", hasUpdate = true))
        assertTrue(PluginListFilter.NEEDS_APPROVAL.keeps("Wants new access", hasUpdate = true))
    }

    @Test
    fun `Updates keeps a plugin that only a newer version can run, and nothing up to date`() {
        assertTrue(PluginListFilter.UPDATES.keeps("Needs an update", hasUpdate = false))
        assertFalse(PluginListFilter.UPDATES.keeps("Running", hasUpdate = false))
    }
}

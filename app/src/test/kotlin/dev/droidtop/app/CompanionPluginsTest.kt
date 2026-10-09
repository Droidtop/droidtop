package dev.droidtop.app

import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AskFirst
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.library.settings.UiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugins on the companion (CompanionPlugins.kt, Droidtop/tracker#414 slice C9). */
class CompanionPluginsTest {
    private val load = AsyncActionItem(id = "pv_load", title = "Load state", confirmTitle = "Load slot 3? Unsaved play is lost.", run = { _, _ -> "Loaded" })
    private val save = ActionItem(id = "pv_save", title = "Save state", run = {})

    @Test fun `a plugin row's confirm flag reaches GameControls`() {
        val on = AskFirst(beforeStopping = true, beforeLoadAndOverwrite = true)
        val off = AskFirst(beforeStopping = true, beforeLoadAndOverwrite = false)
        assertTrue(pluginRowAsks(load, UiMode.FULL, on))
        // Ask before load and overwrite off: the flagged row acts at once.
        assertFalse(pluginRowAsks(load, UiMode.FULL, off))
        // Kid and Kiosk always ask.
        assertTrue(pluginRowAsks(load, UiMode.KID, off))
        assertTrue(pluginRowAsks(load, UiMode.KIOSK, off))
        // A row the plugin did not flag never asks.
        assertFalse(pluginRowAsks(save, UiMode.KID, on))
    }

    @Test fun `a plugin call names the companion surface in the mode`() {
        assertEquals("gaming", pluginMode(SecondaryDisplayContent.Mode.GAMING))
        assertEquals("desktop.companion", dev.droidtop.library.integrations.PluginPanels.surfaceCompanion(pluginMode(SecondaryDisplayContent.Mode.DESKTOP)))
        assertEquals("standard.companion_game", dev.droidtop.library.integrations.PluginPanels.surfaceCompanionGame(pluginMode(SecondaryDisplayContent.Mode.STANDARD)))
    }

    @Test fun `a plugin panel can be a chosen tab, and Plugins lists the rest under More`() {
        val rec = CompanionTab.plugin("acme.rec", "Recorder")
        val chat = CompanionTab.plugin("acme.chat", "Chat")
        val chosen = listOf(CompanionPrefs.id(dev.droidtop.library.settings.ControlPanel.HOME), rec.id)
        val bar = slots(chosen, UiMode.FULL, emptyList(), 0f, emptyMap(), plugins = listOf(rec, chat))
        assertEquals(listOf(CompanionTab.HOME, rec), bar.tabs)
        assertTrue(CompanionTab.PLUGINS in bar.more)
        // Kid and Kiosk have no plugin panels at all.
        val kid = slots(chosen, UiMode.KID, emptyList(), 0f, emptyMap(), plugins = listOf(rec, chat))
        assertFalse(kid.all.any { it.id.startsWith(CompanionPrefs.PLUGIN_PREFIX) || it == CompanionTab.PLUGINS })
    }
}

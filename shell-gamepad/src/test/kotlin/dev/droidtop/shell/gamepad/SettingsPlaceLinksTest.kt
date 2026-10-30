package dev.droidtop.shell.gamepad

import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.Place
import dev.droidtop.library.settings.UiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Settings rows a place keeps (docs/SPEC.md 7j "Places", Droidtop/tracker#759). */
class SettingsPlaceLinksTest {
    @Test
    fun `a place off the left menu keeps its Settings row, a place the menu leads to does not`() {
        val hidden = settingsHiddenPlaceScreens(UiMode.FULL)
        assertFalse("Plugins is in the Quick Menu, not the left menu: Settings is a way in", Place.ID_PLUGINS in hidden)
        assertFalse(Place.ID_SOCIAL in hidden)
        assertFalse(Place.ID_STORES in hidden)
        assertTrue("Updates is a row of the left menu (Droidtop/tracker#792)", Place.ID_UPDATES in hidden)
    }

    @Test
    fun `the Plugins row survives on Accounts and sources`() {
        val plugins = NestedScreenItem(id = "accounts_plugins_screen", title = "Plugins", registryId = Place.ID_PLUGINS)
        val updates = NestedScreenItem(id = "updates_link", title = "Updates", registryId = Place.ID_UPDATES)
        val group = CatalogGroup(id = "accounts_plugins", title = "Plugins and integrations", items = listOf(plugins, updates))
        val kept = withoutPlaceLinks(listOf(group), settingsHiddenPlaceScreens(UiMode.FULL)).single().items
        assertEquals(listOf("accounts_plugins_screen"), kept.map { it.id })
    }

    @Test
    fun `a mode that hides Settings hides no row here, since it shows no places at all`() {
        assertTrue(settingsHiddenPlaceScreens(UiMode.KID).isEmpty())
    }
}

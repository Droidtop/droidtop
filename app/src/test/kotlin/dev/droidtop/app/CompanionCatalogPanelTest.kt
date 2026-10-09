package dev.droidtop.app

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.UiMode
import dev.droidtop.shell.gamepad.QuickSection
import dev.droidtop.shell.gamepad.QuickTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's System tab is the Quick Menu's catalog sections (CompanionCatalogPanel.kt). */
class CompanionCatalogPanelTest {
    private val ids = listOf(
        GamingSettingsCatalog.ID_SYSTEM_SWITCH_MODE,
        GamingSettingsCatalog.ID_SYSTEM_NETWORK,
        GamingSettingsCatalog.ID_SYSTEM_VOLUME,
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS,
        GamingSettingsCatalog.ID_SYSTEM_DND,
        GamingSettingsCatalog.ID_SYSTEM_TIMEOUT,
        GamingSettingsCatalog.ID_SYSTEM_BATTERY,
        GamingSettingsCatalog.ID_AUDIO_OUTPUT,
    )
    private val catalog = listOf(
        CatalogGroup(GamingSettingsCatalog.GROUP_SYSTEM, "System", ids.map { ActionItem(id = it, title = it, run = {}) }),
    )
    private val performance = AsyncActionItem(id = GamingSettingsCatalog.ID_PERFORMANCE_MODE, title = "Performance mode", run = { _, _ -> "" })
    private val privacy = ActionItem(id = GamingSettingsCatalog.ID_PRIVACY_DASHBOARD, title = "Privacy dashboard", run = {})

    private fun quick(section: QuickSection) = QuickTiles.sectionGroups(catalog, section, UiMode.FULL).flatMap { it.items }

    @Test fun cardsCarryTheQuickMenusItemsInTheSameOrder() {
        val cards = companionSystemCards(::quick, performance, privacy)
        assertEquals(listOf("system", "display", "sound", "power", "storage", "privacy"), cards.map { it.id })
        assertEquals(quick(QuickSection.SYSTEM).map { it.id }, cards.first { it.id == "system" }.items.map { it.id })
        assertEquals(quick(QuickSection.DISPLAY).map { it.id }, cards.first { it.id == "display" }.items.map { it.id })
        assertEquals(quick(QuickSection.AUDIO).map { it.id }, cards.first { it.id == "sound" }.items.map { it.id })
        // Every Quick Menu item is on the companion exactly once.
        val all = cards.flatMap { card -> card.items.map { it.id } }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.containsAll(ids))
    }

    @Test fun performanceModeIsOneCatalogIdOnPower() {
        val cards = companionSystemCards(::quick, performance, privacy)
        assertEquals(listOf(GamingSettingsCatalog.ID_PERFORMANCE_MODE), cards.first { it.id == "power" }.items.map { it.id })
        // No provider or no game: no performance mode row, never a dead one; the card stays for the battery and
        // clocks it draws itself (slice C16).
        assertEquals(emptyList<String>(), companionSystemCards(::quick, null, privacy).first { it.id == "power" }.items.map { it.id })
    }

    @Test fun onlyTheSystemCardStartsOpen() {
        val cards = companionSystemCards(::quick, performance, privacy)
        assertEquals(listOf("system"), cards.filter { !it.folded }.map { it.id })
    }
}

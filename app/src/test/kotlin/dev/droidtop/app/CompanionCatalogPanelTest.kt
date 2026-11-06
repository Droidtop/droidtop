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
        assertEquals(listOf("system", "display", "sound", "connections", "power", "storage", "privacy"), cards.map { it.id })
        // The Android settings row is Device's "Android's pages" on the companion (Droidtop/tracker#786 S3).
        assertEquals(
            quick(QuickSection.SYSTEM).map { it.id } - GamingSettingsCatalog.ID_SYSTEM_ANDROID_LINKS,
            cards.first { it.id == "system" }.items.map { it.id },
        )
        // Brightness is drawn by the built-in screen's card above Display's rows (Droidtop/tracker#786 S7).
        assertEquals(quick(QuickSection.DISPLAY).map { it.id } - ON_SCREEN_CARD, cards.first { it.id == "display" }.items.map { it.id })
        assertEquals(quick(QuickSection.AUDIO).map { it.id }, cards.first { it.id == "sound" }.items.map { it.id })
        // Every Quick Menu item is on the companion exactly once.
        val all = cards.flatMap { card -> card.items.map { it.id } }
        assertEquals(all.size, all.toSet().size)
        assertTrue((all + ON_SCREEN_CARD).containsAll(ids))
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

    @Test
    fun `a screen's titled groups keep their names over their first row, the page top does not`() {
        val a = ActionItem(id = "a1", title = "One", run = {})
        val b = ActionItem(id = "b1", title = "Two", run = {})
        val c = ActionItem(id = "c1", title = "Three", run = {})
        val groups = listOf(
            CatalogGroup(id = dev.droidtop.library.settings.CatalogPage.TOP_GROUP_ID, title = "Header", items = listOf(a)),
            CatalogGroup(id = "keys", title = "Keys", items = listOf(b)),
            CatalogGroup(id = "untitled", title = null, items = listOf(c)),
            CatalogGroup(id = "empty", title = "Empty", items = emptyList()),
        )
        assertEquals(mapOf("b1" to "Keys"), companionHeadings(groups))
    }

    // Droidtop/tracker#786 S7: font and display size are on Accessibility, not twice.
    @Test fun fontAndDisplaySizeAreOnAccessibilityOnly() {
        val font = ActionItem(id = dev.droidtop.runtime.systemstatus.AccessibilityControls.ID_FONT, title = "Font size", run = {})
        val withFont: (QuickSection) -> List<dev.droidtop.library.settings.CatalogItem> = { section ->
            if (section == QuickSection.DISPLAY) quick(section) + font else quick(section)
        }
        val cards = companionSystemCards(withFont, performance, privacy, accessibility = listOf(font))
        val all = cards.flatMap { card -> card.items.map { it.id } }
        assertEquals(1, all.count { it == font.id })
        assertTrue(cards.first { it.id == "accessibility" }.items.any { it.id == font.id })
        // Without the Accessibility card's own row, Display keeps it: nothing leaves the companion.
        assertTrue(companionSystemCards(withFont, performance, privacy).first { it.id == "display" }.items.any { it.id == font.id })
    }
}

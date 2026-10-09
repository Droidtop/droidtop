package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one restriction model (ControlAccess.kt, docs/SPEC.md "UI modes and ControlAccess"). */
class ControlAccessTest {

    private val restrictedCompanion = setOf(ControlPanel.HOME, ControlPanel.GAME, ControlPanel.PERFORMANCE)
    private val restrictedRows = setOf(ControlRow.PINS, ControlRow.GAME_RESUME, ControlRow.GAME_QUIT, ControlRow.STATS)

    /**
     * Every mode's sets, written out. A mode added to [UiMode] (Guest is reserved) is missing here and fails
     * this test until its sets are decided and written down.
     */
    private val expected: Map<UiMode, Triple<Set<ControlPanel>, Set<ControlPanel>, Set<ControlRow>>> = mapOf(
        UiMode.FULL to Triple(
            setOf(
                ControlPanel.HOME, ControlPanel.GAME, ControlPanel.SYSTEM, ControlPanel.APPS, ControlPanel.PERFORMANCE,
                ControlPanel.INPUT, ControlPanel.SOCIAL, ControlPanel.PLUGINS,
            ),
            ControlPanel.entries.toSet(),
            ControlRow.entries.toSet(),
        ),
        UiMode.KIOSK to Triple(restrictedCompanion, ControlPanel.entries.toSet(), restrictedRows),
        UiMode.KID to Triple(restrictedCompanion, ControlPanel.entries.toSet(), restrictedRows),
    )

    @Test
    fun `every mode has its tab and row sets written out`() {
        assertEquals("a UI mode has no written sets", UiMode.entries.toSet(), expected.keys)
        for ((mode, sets) in expected) {
            val (companion, quickMenu, rows) = sets
            assertEquals("$mode companion tabs", companion, ControlAccess.panels(mode, ControlSurface.COMPANION))
            assertEquals("$mode Quick Menu sections", quickMenu, ControlAccess.panels(mode, ControlSurface.QUICK_MENU))
            assertEquals("$mode rows", rows, ControlRow.entries.filter { ControlAccess.shows(mode, it) }.toSet())
        }
    }

    @Test
    fun `Kid and Kiosk show Performance read-only, Full does not`() {
        assertTrue(ControlAccess.readOnly(UiMode.KID, ControlPanel.PERFORMANCE))
        assertTrue(ControlAccess.readOnly(UiMode.KIOSK, ControlPanel.PERFORMANCE))
        assertFalse(ControlAccess.readOnly(UiMode.FULL, ControlPanel.PERFORMANCE))
        assertFalse(ControlAccess.shows(UiMode.KID, ControlRow.PERFORMANCE_CONTROLS))
        assertTrue(ControlAccess.shows(UiMode.KID, ControlRow.STATS))
    }

    @Test
    fun `the items that control the restriction are hidden in Kid and Kiosk only`() {
        val ids = listOf(GamingSettingsCatalog.ID_UI_MODE, ControlAccess.ID_KID_VOLUME_CAP, ControlAccess.ID_UI_MODE_PASSKEY)
        for (mode in listOf(UiMode.KID, UiMode.KIOSK)) {
            ids.forEach { assertFalse("$mode shows $it", ControlAccess.shows(mode, it)) }
            assertFalse(ControlAccess.shows(mode, "pref_companion_anything", ControlAccess.GROUP_COMPANION))
        }
        ids.forEach { assertTrue(ControlAccess.shows(UiMode.FULL, it)) }
        assertTrue(ControlAccess.shows(UiMode.FULL, "pref_companion_anything", ControlAccess.GROUP_COMPANION))
    }

    @Test
    fun `filter drops the Companion group and the hidden items, and keeps the way out`() {
        fun action(id: String) = ActionItem(id = id, title = id, run = {})
        val groups = listOf(
            CatalogGroup(GamingSettingsCatalog.GROUP_GAMING, "Gaming", listOf(action(GamingSettingsCatalog.ID_UI_MODE), action("other"))),
            CatalogGroup(ControlAccess.GROUP_COMPANION, "Companion", listOf(action("pref_companion_tabs"))),
            CatalogGroup(GamingSettingsCatalog.GROUP_SYSTEM, "System", listOf(action(GamingSettingsCatalog.ID_SYSTEM_LEAVE_UI_MODE))),
        )
        val kid = ControlAccess.filter(UiMode.KID, groups)
        assertEquals(listOf(GamingSettingsCatalog.GROUP_GAMING, GamingSettingsCatalog.GROUP_SYSTEM), kid.map { it.id })
        assertEquals(listOf("other"), kid.first().items.map { it.id })
        assertEquals(listOf(GamingSettingsCatalog.ID_SYSTEM_LEAVE_UI_MODE), kid.last().items.map { it.id })
        assertEquals(groups, ControlAccess.filter(UiMode.FULL, groups))
    }

    @Test
    fun `Kid pins only volume and brightness, always asks, and has no extras`() {
        assertTrue(ControlAccess.pinnable(UiMode.KID, GamingSettingsCatalog.ID_SYSTEM_VOLUME))
        assertTrue(ControlAccess.pinnable(UiMode.KID, GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS))
        assertFalse(ControlAccess.pinnable(UiMode.KID, GamingSettingsCatalog.ID_SYSTEM_NETWORK))
        assertTrue(ControlAccess.pinnable(UiMode.FULL, GamingSettingsCatalog.ID_SYSTEM_NETWORK))
        val kid = ControlAccess.rules(UiMode.KID)
        assertTrue(kid.alwaysAsk)
        assertFalse(kid.longPress || kid.openOnCompanion || ControlAccess.shows(UiMode.KID, ControlRow.PROVIDER_LINE))
        assertFalse(ControlAccess.rules(UiMode.FULL).alwaysAsk)
    }

    @Test
    fun `UiMode reads its settings and kid-game answers from ControlAccess`() {
        assertFalse(UiMode.FULL.hidesSettings)
        assertTrue(UiMode.KIOSK.hidesSettings && UiMode.KID.hidesSettings)
        assertTrue(UiMode.KID.kidGamesOnly)
        assertFalse(UiMode.KIOSK.kidGamesOnly || UiMode.FULL.kidGamesOnly)
    }
}

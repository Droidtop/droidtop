package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's navigation preferences (CompanionPrefs.kt). */
class CompanionPrefsTest {

    @Test
    fun `old second-screen roles become opening tabs only where they differ from the default`() {
        val migrated = CompanionPrefs.migrateRoles(
            mapOf(
                "pref_second_screen_role_GAMING" to "INPUT",
                "pref_second_screen_role_DESKTOP" to "COMPANION",
                "pref_second_screen_role_STANDARD" to "COMPANION",
                "unrelated" to "INPUT",
            ),
        )
        assertEquals(mapOf("GAMING" to "input", "DESKTOP" to "home"), migrated)
        assertTrue(CompanionPrefs.migrateRoles(mapOf("pref_second_screen_role_DESKTOP" to "INPUT")).isEmpty())
        assertTrue(CompanionPrefs.migrateRoles(mapOf("pref_second_screen_role_GAMING" to "SOMETHING")).isEmpty())
    }

    @Test
    fun `stored ids are read as today's, unknown ones dropped, at most four`() {
        assertEquals(listOf("home", "apps", "social", "system"), CompanionPrefs.decodeList("home,tasks,nonsense,social,home,system,input"))
        val read = CompanionPrefs.read(
            mapOf("chosen_GAMING" to "tasks,home", "opening_STANDARD" to "tasks", "game_start" to "none", "bar_tip_seen" to true),
        )
        assertEquals(listOf("apps", "home"), read.chosen("GAMING"))
        assertEquals(CompanionPrefs.defaultChosen("DESKTOP"), read.chosen("DESKTOP"))
        assertEquals("apps", read.opening("STANDARD"))
        assertEquals(CompanionPrefs.OPEN_DEFAULT, read.opening("GAMING"))
        assertEquals(CompanionPrefs.GAME_START_NONE, read.onGameStart)
        assertTrue(read.barTipSeen)
    }

    @Test
    fun `setting a bar slot replaces it, moves a tab already there, and closes gaps`() {
        val bar = listOf("home", "system", "apps", "social")
        assertEquals(listOf("home", "performance", "apps", "social"), CompanionPrefs.placeTab(bar, 1, "performance"))
        assertEquals(listOf("system", "home", "social"), CompanionPrefs.placeTab(bar, 2, "home"))
        assertEquals(listOf("home", "apps", "social"), CompanionPrefs.placeTab(bar, 1, ""))
        assertEquals(listOf("home", "input"), CompanionPrefs.placeTab(listOf("home"), 3, "input"))
    }

    @Test
    fun `plugin ids name the Plugins panel`() {
        assertEquals(ControlPanel.PLUGINS, CompanionPrefs.panelOf("plugin:dev.example"))
        assertEquals(ControlPanel.APPS, CompanionPrefs.panelOf("apps"))
        assertEquals(null, CompanionPrefs.panelOf("tasks"))
    }
}

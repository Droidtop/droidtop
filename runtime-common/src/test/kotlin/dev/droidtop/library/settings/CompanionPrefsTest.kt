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
        assertEquals(listOf("home", CompanionPrefs.DEVICE_APPS, "social", "device"), CompanionPrefs.decodeList("home,tasks,nonsense,social,home,system,input"))
        val read = CompanionPrefs.read(
            mapOf("chosen_GAMING" to "tasks,home", "opening_STANDARD" to "tasks", "game_start" to "none", "bar_tip_seen" to true),
        )
        assertEquals(listOf(CompanionPrefs.DEVICE_APPS, "home"), read.chosen("GAMING"))
        assertEquals(CompanionPrefs.defaultChosen("DESKTOP"), read.chosen("DESKTOP"))
        assertEquals(CompanionPrefs.DEVICE_APPS, read.opening("STANDARD"))
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

    @Test
    fun `a page tab names its parent panel, so the mode's rule for the panel holds for the page`() {
        val display = CompanionPrefs.pageId(ControlPanel.DEVICE, "display")
        assertEquals("page:device/display", display)
        assertEquals(ControlPanel.DEVICE, CompanionPrefs.panelOf(display))
        assertEquals(ControlPanel.DROIDTOP, CompanionPrefs.panelOf(CompanionPrefs.pageId(ControlPanel.DROIDTOP, "backup")))
        // Only Device's and droidtop's pages can be tabs; a page with no name, or under another panel, is nothing.
        assertEquals(null, CompanionPrefs.panelOf("page:device/"))
        assertEquals(null, CompanionPrefs.panelOf("page:home/pins"))
    }

    // Droidtop/tracker#786 S3: a saved bar keeps its reach when System, Network and Apps move under Device.
    @Test
    fun `the old System, Network and Apps tabs become Device and its pages`() {
        assertEquals(
            listOf("home", "device", CompanionPrefs.DEVICE_NETWORK, CompanionPrefs.DEVICE_APPS),
            CompanionPrefs.decodeList("home,system,connections,apps"),
        )
        assertEquals(ControlPanel.DEVICE, CompanionPrefs.parentOf(CompanionPrefs.DEVICE_NETWORK))
        assertEquals(ControlPanel.DROIDTOP, CompanionPrefs.parentOf(CompanionPrefs.DROIDTOP_COMPANION))
        assertEquals(null, CompanionPrefs.parentOf("home"))
    }

    @Test
    fun `the default bars lead with Device and droidtop`() {
        assertEquals(listOf("home", "device", "droidtop", "social"), CompanionPrefs.defaultChosen("GAMING"))
        assertEquals(listOf("input", "home", "device", "droidtop"), CompanionPrefs.defaultChosen("DESKTOP"))
    }
}

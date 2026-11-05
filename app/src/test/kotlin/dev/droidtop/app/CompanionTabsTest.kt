package dev.droidtop.app

import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.library.settings.CompanionSettings
import dev.droidtop.library.settings.UiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's bar (CompanionTabs.kt `slots`, docs/SPEC.md "The companion's tabs"). */
class CompanionTabsTest {
    private val gamingDefault = CompanionPrefs.defaultChosen("GAMING")
    private val desktopDefault = CompanionPrefs.defaultChosen("DESKTOP")

    private fun ids(tabs: List<CompanionTab>) = tabs.map { it.id }

    @Test fun gamingDefaultsThenMore() {
        val bar = slots(gamingDefault, UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(listOf("home", "device", "droidtop", "social"), ids(bar.tabs))
        assertEquals(listOf("performance", "input"), ids(bar.more))
    }

    @Test fun desktopLeadsWithInput() {
        val bar = slots(desktopDefault, UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(listOf("input", "home", "device", "droidtop"), ids(bar.tabs))
        assertEquals(listOf("performance", "social"), ids(bar.more))
    }

    @Test fun aGameAddsGameAfterTheChosenTabsWithoutMovingThem() {
        val running = runningTabs(gameRunning = true, wantsInput = runnerWantsInput(LibraryEntryKind.CONSOLE_ROM))
        val bar = slots(gamingDefault, UiMode.FULL, running, 0f, emptyMap())
        assertEquals(listOf("home", "device", "droidtop", "social", "game"), ids(bar.tabs))
        assertFalse(bar.more.any { it.id == "game" })
    }

    @Test fun streamsAndPcGamesAddInputUnlessChosen() {
        for (kind in listOf(LibraryEntryKind.REMOTE_STREAM, LibraryEntryKind.COMPUTER_GAME, LibraryEntryKind.WINE_PROFILE)) {
            val running = runningTabs(gameRunning = true, wantsInput = runnerWantsInput(kind))
            val gaming = slots(gamingDefault, UiMode.FULL, running, 0f, emptyMap())
            assertEquals(listOf("home", "device", "droidtop", "social", "game", "input"), ids(gaming.tabs))
            assertEquals(listOf("performance"), ids(gaming.more))
            // Desktop already has Input chosen: it stays in its place and is not added twice.
            val desktop = slots(desktopDefault, UiMode.FULL, running, 0f, emptyMap())
            assertEquals(listOf("input", "home", "device", "droidtop", "game"), ids(desktop.tabs))
        }
        assertEquals(listOf(CompanionTab.GAME), runningTabs(true, runnerWantsInput(LibraryEntryKind.RENPY)))
        assertTrue(runningTabs(false, true).isEmpty())
    }

    @Test fun orderIsThePersonsAndGameCannotBeChosen() {
        val bar = slots(listOf("social", "game", "home", "home", "performance", "system"), UiMode.FULL, emptyList(), 0f, emptyMap())
        // An old "system" is Device now.
        assertEquals(listOf("social", "home", "performance", "device"), ids(bar.tabs))
    }

    @Test fun oldTabIdsStillName() {
        val bar = slots(listOf("home", "tasks"), UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(listOf("home", CompanionPrefs.DEVICE_APPS), ids(bar.tabs))
    }

    @Test fun kidShowsOnlyHomeGameAndPerformance() {
        val running = runningTabs(gameRunning = true, wantsInput = false)
        val bar = slots(gamingDefault, UiMode.KID, running, 0f, emptyMap())
        assertEquals(listOf("home", "performance", "game"), ids(bar.tabs))
        assertTrue(bar.more.isEmpty())
        assertEquals(ids(bar.tabs), ids(slots(gamingDefault, UiMode.KIOSK, running, 0f, emptyMap()).tabs))
        // A stream's Input is not offered in Kid either.
        assertFalse("input" in ids(slots(gamingDefault, UiMode.KID, runningTabs(true, true), 0f, emptyMap()).all))
    }

    @Test fun atTheLargestFontChosenTabsMoveIntoMoreFromTheRightAndComeBack() {
        val sizes = mapOf("home" to 100f, "device" to 100f, "droidtop" to 100f, "social" to 100f, "game" to 100f)
        // Room for everything: four tabs and More.
        val roomy = slots(gamingDefault, UiMode.FULL, emptyList(), 500f, sizes, moreSize = 100f)
        assertEquals(listOf("home", "device", "droidtop", "social"), ids(roomy.tabs))
        // Labels grew: room for three and More, so Social goes first into More, at its head.
        val tight = slots(gamingDefault, UiMode.FULL, emptyList(), 420f, sizes, moreSize = 100f)
        assertEquals(listOf("home", "device", "droidtop"), ids(tight.tabs))
        assertEquals(listOf("social", "performance", "input"), ids(tight.more))
        // A running game takes room too; the chosen tabs give it up from the right, Game stays.
        val playing = slots(gamingDefault, UiMode.FULL, runningTabs(true, false), 420f, sizes, moreSize = 100f)
        assertEquals(listOf("home", "device", "game"), ids(playing.tabs))
        assertEquals(listOf("droidtop", "social", "performance", "input"), ids(playing.more))
        // Unmeasured: nothing moves.
        assertEquals(ids(roomy.tabs), ids(slots(gamingDefault, UiMode.FULL, emptyList(), 0f, sizes, 100f).tabs))
    }

    @Test fun moreCarriesTheBadgesOfTabsUnderIt() {
        val desktop = slots(desktopDefault, UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(3, moreBadge(desktop, mapOf("social" to 3)))
        val gaming = slots(gamingDefault, UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(0, moreBadge(gaming, mapOf("social" to 3)))
    }

    @Test fun aPluginPanelCanBeAChosenTabAndPluginsSitsUnderMore() {
        val plugin = CompanionTab.plugin("dev.example.retro", "RetroArch")
        val chosen = listOf("home", plugin.id, "device")
        val bar = slots(chosen, UiMode.FULL, emptyList(), 0f, emptyMap(), plugins = listOf(plugin))
        assertEquals(listOf("home", plugin.id, "device"), ids(bar.tabs))
        assertEquals("plugins", bar.more.last().id)
        // Without plugin panels there is no Plugins entry at all, and Kid never gets one.
        assertFalse(slots(chosen, UiMode.FULL, emptyList(), 0f, emptyMap()).all.any { it.id == "plugins" })
        assertFalse(slots(chosen, UiMode.KID, emptyList(), 0f, emptyMap(), plugins = listOf(plugin)).all.any { it.id == plugin.id })
    }

    @Test fun openingTabFollowsTheSettingAndFallsBackToHome() {
        val bar = slots(gamingDefault, UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals("home", openingTab(CompanionSettings(), "GAMING", bar))
        val desktop = slots(desktopDefault, UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals("input", openingTab(CompanionSettings(), "DESKTOP", desktop))
        val last = CompanionSettings(opening = mapOf("GAMING" to CompanionPrefs.OPEN_LAST), last = mapOf("GAMING" to "performance"))
        assertEquals("performance", openingTab(last, "GAMING", bar))
        val kid = slots(gamingDefault, UiMode.KID, emptyList(), 0f, emptyMap())
        assertEquals("home", openingTab(CompanionSettings(opening = mapOf("GAMING" to "system")), "GAMING", kid))
    }

    @Test fun aGameStartTurnsToTheRunnersTab() {
        val game = runningTabs(true, false)
        val stream = runningTabs(true, true)
        val gameBar = slots(gamingDefault, UiMode.FULL, game, 0f, emptyMap())
        val streamBar = slots(gamingDefault, UiMode.FULL, stream, 0f, emptyMap())
        assertEquals("game", tabForGameStart(CompanionPrefs.GAME_START_RUNNER, game, gameBar))
        assertEquals("input", tabForGameStart(CompanionPrefs.GAME_START_RUNNER, stream, streamBar))
        assertNull(tabForGameStart(CompanionPrefs.GAME_START_NONE, game, gameBar))
        assertEquals("performance", tabForGameStart("performance", game, gameBar))
        assertNull(tabForGameStart("social", game, slots(gamingDefault, UiMode.KID, game, 0f, emptyMap())))
    }

    @Test fun aRunningJobOnHomeHoldsTheCompanionOn() {
        assertTrue(jobShownOnHome(CompanionTab.HOME.id, jobRunning = true))
        assertFalse(jobShownOnHome(CompanionTab.HOME.id, jobRunning = false))
        assertFalse(jobShownOnHome(CompanionTab.APPS.id, jobRunning = true))
    }

    @Test fun androidsBarsGiveWayToDroidtopsStatusOutsideStandard() {
        assertTrue(companionImmersive(dev.droidtop.display.SecondaryDisplayContent.Mode.GAMING))
        assertTrue(companionImmersive(dev.droidtop.display.SecondaryDisplayContent.Mode.DESKTOP))
        assertFalse(companionImmersive(dev.droidtop.display.SecondaryDisplayContent.Mode.STANDARD))
    }

    @Test
    fun `however short the bar, Home never moves into More`() {
        val sizes = mapOf("home" to 100f, "device" to 100f, "droidtop" to 100f, "social" to 100f)
        // Room for More alone: every tab but Home and Device moves under it (Droidtop/tracker#786 plan 4.2).
        val squeezed = slots(gamingDefault, UiMode.FULL, emptyList(), 120f, sizes, moreSize = 100f)
        assertEquals(listOf("home", "device"), ids(squeezed.tabs))
        assertEquals(listOf("droidtop", "social"), ids(squeezed.more).take(2))
    }

    // Droidtop/tracker#786 S3: a page can be a tab of its own, and is never listed under More.
    @Test
    fun `a page chosen as a tab sits on the bar and pages are never under More`() {
        val bar = slots(listOf("home", CompanionPrefs.DEVICE_NETWORK), UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(listOf("home", CompanionPrefs.DEVICE_NETWORK), ids(bar.tabs))
        assertFalse(bar.more.any { it.id.startsWith(CompanionPrefs.PAGE_PREFIX) })
        assertEquals(CompanionTab.DEVICE_PAGES, CompanionTab.pagesOf(CompanionTab.DEVICE.id))
        // Kid gets neither Device nor its pages.
        assertFalse(slots(listOf(CompanionPrefs.DEVICE_NETWORK), UiMode.KID, emptyList(), 0f, emptyMap()).all.any { it.id == CompanionPrefs.DEVICE_NETWORK })
    }

    @Test
    fun `an exit row names the page as Android's`() {
        assertEquals("Android's Display", exitLabel("Display"))
    }
}

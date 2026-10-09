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
        assertEquals(listOf("home", "system", "apps", "social"), ids(bar.tabs))
        assertEquals(listOf("performance", "input"), ids(bar.more))
    }

    @Test fun desktopLeadsWithInput() {
        val bar = slots(desktopDefault, UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(listOf("input", "home", "system", "apps"), ids(bar.tabs))
        assertEquals(listOf("performance", "social"), ids(bar.more))
    }

    @Test fun aGameAddsGameAfterTheChosenTabsWithoutMovingThem() {
        val running = runningTabs(gameRunning = true, wantsInput = runnerWantsInput(LibraryEntryKind.CONSOLE_ROM))
        val bar = slots(gamingDefault, UiMode.FULL, running, 0f, emptyMap())
        assertEquals(listOf("home", "system", "apps", "social", "game"), ids(bar.tabs))
        assertFalse(bar.more.any { it.id == "game" })
    }

    @Test fun streamsAndPcGamesAddInputUnlessChosen() {
        for (kind in listOf(LibraryEntryKind.REMOTE_STREAM, LibraryEntryKind.WINE_PROFILE)) {
            val running = runningTabs(gameRunning = true, wantsInput = runnerWantsInput(kind))
            val gaming = slots(gamingDefault, UiMode.FULL, running, 0f, emptyMap())
            assertEquals(listOf("home", "system", "apps", "social", "game", "input"), ids(gaming.tabs))
            assertEquals(listOf("performance"), ids(gaming.more))
            // Desktop already has Input chosen: it stays in its place and is not added twice.
            val desktop = slots(desktopDefault, UiMode.FULL, running, 0f, emptyMap())
            assertEquals(listOf("input", "home", "system", "apps", "game"), ids(desktop.tabs))
        }
        assertEquals(listOf(CompanionTab.GAME), runningTabs(true, runnerWantsInput(LibraryEntryKind.RENPY)))
        assertTrue(runningTabs(false, true).isEmpty())
    }

    @Test fun orderIsThePersonsAndGameCannotBeChosen() {
        val bar = slots(listOf("social", "game", "home", "home", "performance", "system"), UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(listOf("social", "home", "performance", "system"), ids(bar.tabs))
    }

    @Test fun oldTabIdsStillName() {
        val bar = slots(listOf("home", "tasks"), UiMode.FULL, emptyList(), 0f, emptyMap())
        assertEquals(listOf("home", "apps"), ids(bar.tabs))
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
        val sizes = mapOf("home" to 100f, "system" to 100f, "apps" to 100f, "social" to 100f, "game" to 100f)
        // Room for everything: four tabs and More.
        val roomy = slots(gamingDefault, UiMode.FULL, emptyList(), 500f, sizes, moreSize = 100f)
        assertEquals(listOf("home", "system", "apps", "social"), ids(roomy.tabs))
        // Labels grew: room for three and More, so Social goes first into More, at its head.
        val tight = slots(gamingDefault, UiMode.FULL, emptyList(), 420f, sizes, moreSize = 100f)
        assertEquals(listOf("home", "system", "apps"), ids(tight.tabs))
        assertEquals(listOf("social", "performance", "input"), ids(tight.more))
        // A running game takes room too; the chosen tabs give it up from the right, Game stays.
        val playing = slots(gamingDefault, UiMode.FULL, runningTabs(true, false), 420f, sizes, moreSize = 100f)
        assertEquals(listOf("home", "system", "game"), ids(playing.tabs))
        assertEquals(listOf("apps", "social", "performance", "input"), ids(playing.more))
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
        val chosen = listOf("home", plugin.id, "system")
        val bar = slots(chosen, UiMode.FULL, emptyList(), 0f, emptyMap(), plugins = listOf(plugin))
        assertEquals(listOf("home", plugin.id, "system"), ids(bar.tabs))
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

    @Test fun storageTextReadsInGigabytes() {
        assertEquals("12.3 GB free of 128.0 GB", storageText(12_300_000_000L, 128_000_000_000L))
    }
}

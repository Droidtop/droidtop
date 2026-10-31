package dev.droidtop.shell.gamepad

import dev.droidtop.library.achievements.RaResult
import dev.droidtop.library.stores.StoreAchievement
import dev.droidtop.library.stores.StoreAchievements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Achievements row before and without a key (docs/SPEC.md 7h, "RetroAchievements"; Droidtop/tracker#143). */
class AchievementRowsTest {
    @Test
    fun aGameRetroAchievementsCoversAsksForASignInWhenNoKeyIsStored() {
        val row = achievementRowsFor(RaResult.NotSignedIn).single()
        assertEquals("Achievements", row.title)
        assertEquals("Sign in", row.value)
        assertEquals(ACHIEVEMENTS_SIGN_IN, row.subtitle)
        assertNotNull("A opens the guided key setup in place", row.run)
    }

    @Test
    fun aConsoleItDoesNotCoverHasNoRowAndBeingOfflineSaysNothing() {
        assertTrue(achievementRowsFor(RaResult.Unsupported).isEmpty())
        assertTrue(achievementRowsFor(RaResult.Failed("No connection to RetroAchievements")).isEmpty())
        assertEquals("Not loaded", achievementRowsFor(RaResult.Failed("Refused")).single().value)
    }

    @Test
    fun aGameRecognisedOnlyByItsTitleIsAskedNotShown() {
        val game = dev.droidtop.library.achievements.RaGame(42, "Super Metroid", 3, 74, 610, emptyList())
        val row = achievementRowsFor(RaResult.Proposed(game), "/roms/snes/sm.sfc").single()
        assertEquals("Is it Super Metroid?", row.value)
        assertNotNull("the answers open in place of the row", row.screen)
    }

    private val list = StoreAchievements(
        listOf(
            StoreAchievement("A", "First steps", "Finish the tutorial", earnedAtMs = 1_700_000_000_000L),
            StoreAchievement("B", "Secret ending", "See the true ending", earnedAtMs = null, hidden = true),
            StoreAchievement("C", "Collector", "Find 100 shells", earnedAtMs = null, progress = 45f, progressMax = 100f),
        ),
        pageUrl = "https://steamcommunity.com/profiles/1/stats/220/achievements",
    )

    @Test
    fun aStoreGameShowsItsAchievementsEarnedOfAllAndOpensThePage() {
        val row = storeAchievementRowsFor("Steam", "220", Result.success(list)).single()
        assertEquals("Achievements", row.title)
        assertEquals("1 of 3", row.value)
        assertNotNull(row.screen)
    }

    @Test
    fun aSecretOneIsNotDescribedUntilEarnedAndACountedOneShowsHowFar() {
        assertEquals("Hidden achievement", storeAchievementItem(list.achievements[1]).title)
        assertEquals("45 of 100", storeAchievementItem(list.achievements[2]).value)
        assertEquals("First steps", storeAchievementItem(list.achievements[0]).title)
    }

    @Test
    fun noAchievementsNobodySignedInOrOfflineIsNoRowAndOtherFailuresSaySo() {
        assertTrue(storeAchievementRowsFor("Steam", "220", Result.success(StoreAchievements(emptyList()))).isEmpty())
        assertTrue(storeAchievementRowsFor("Steam", "220", null).isEmpty())
        assertTrue(storeAchievementRowsFor("Steam", "220", Result.failure(IllegalStateException("Steam could not be reached. Check the network and try again"))).isEmpty())
        assertEquals("Not loaded", storeAchievementRowsFor("Steam", "220", Result.failure(IllegalStateException("Steam did not give the achievements (AccessDenied)"))).single().value)
    }
}

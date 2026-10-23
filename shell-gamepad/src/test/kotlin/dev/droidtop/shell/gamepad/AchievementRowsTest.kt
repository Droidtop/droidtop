package dev.droidtop.shell.gamepad

import dev.droidtop.library.achievements.RaResult
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
        assertNotNull("A and the chip open the guided key setup in place", row.run)
        assertNotNull(row.chip)
    }

    @Test
    fun aConsoleItDoesNotCoverHasNoRowAndBeingOfflineSaysNothing() {
        assertTrue(achievementRowsFor(RaResult.Unsupported).isEmpty())
        assertTrue(achievementRowsFor(RaResult.Failed("No connection to RetroAchievements")).isEmpty())
        assertEquals("Not loaded", achievementRowsFor(RaResult.Failed("Refused")).single().value)
    }
}

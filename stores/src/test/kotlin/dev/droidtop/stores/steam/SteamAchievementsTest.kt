package dev.droidtop.stores.steam

import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Steam's achievement blocks as droidtop shows them (Droidtop/tracker#230). */
class SteamAchievementsTest {
    @Test
    fun earnedOnesCarryTheirTimeAndIconAndLockedOnesTheGrayIcon() {
        val shown = SteamAchievements.achievementsOf(
            220,
            listOf(
                AchievementBlocks(1, listOf(1_700_000_000), name = "ACH_A", displayName = "First steps", description = "Finish the tutorial", icon = "a.jpg", iconGray = "a_gray.jpg"),
                AchievementBlocks(2, listOf(0), name = "ACH_B", displayName = "Collector", description = "Find 100 shells", icon = "b.jpg", iconGray = "b_gray.jpg", progressCurrent = 45f, progressMax = 100f),
                AchievementBlocks(3, listOf(0)),
            ),
        )
        assertEquals(listOf("First steps", "Collector"), shown.map { it.title })
        assertEquals(1_700_000_000_000L, shown[0].earnedAtMs)
        assertEquals("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/220/a.jpg", shown[0].iconUrl)
        assertNull(shown[1].earnedAtMs)
        assertEquals("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/220/b_gray.jpg", shown[1].iconUrl)
        assertEquals(45f, shown[1].progress)
        assertEquals(100f, shown[1].progressMax)
    }
}

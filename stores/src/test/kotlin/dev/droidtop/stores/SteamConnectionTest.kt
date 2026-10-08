package dev.droidtop.stores

import dev.droidtop.library.stores.SocialState
import dev.droidtop.stores.steam.SteamBackoff
import dev.droidtop.stores.steam.SteamFriendsHub
import `in`.dragonbra.javasteam.enums.EPersonaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the kept Steam connection retries, and how a friend's state is read (docs/SPEC.md 7g, "Stores"). */
class SteamConnectionTest {
    @Test
    fun `the retry pause doubles from five seconds and stops at five minutes`() {
        val middle = (0..8).map { SteamBackoff.delayMs(it, jitter = 0.5) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L), middle)
    }

    @Test
    fun `the pause is spread over eighty to a hundred and twenty percent`() {
        assertEquals(4_000L, SteamBackoff.delayMs(0, jitter = 0.0))
        assertEquals(6_000L, SteamBackoff.delayMs(0, jitter = 1.0))
        assertEquals(4_000L, SteamBackoff.delayMs(0, jitter = -3.0))
        assertTrue(SteamBackoff.delayMs(40, jitter = 1.0) <= (SteamBackoff.CAP_MS * 1.2).toLong())
    }

    @Test
    fun `a friend's Steam state becomes one of droidtop's`() {
        assertEquals(SocialState.OFFLINE, SteamFriendsHub.stateOf(EPersonaState.Offline, inGame = false))
        assertEquals(SocialState.OFFLINE, SteamFriendsHub.stateOf(EPersonaState.Invisible, inGame = true))
        assertEquals(SocialState.ONLINE, SteamFriendsHub.stateOf(EPersonaState.Online, inGame = false))
        assertEquals(SocialState.IN_GAME, SteamFriendsHub.stateOf(EPersonaState.Online, inGame = true))
        assertEquals(SocialState.IN_GAME, SteamFriendsHub.stateOf(EPersonaState.Away, inGame = true))
        assertEquals(SocialState.BUSY, SteamFriendsHub.stateOf(EPersonaState.Busy, inGame = false))
        assertEquals(SocialState.AWAY, SteamFriendsHub.stateOf(EPersonaState.Snooze, inGame = false))
        assertEquals(SocialState.ONLINE, SteamFriendsHub.stateOf(EPersonaState.LookingToTrade, inGame = false))
    }
}

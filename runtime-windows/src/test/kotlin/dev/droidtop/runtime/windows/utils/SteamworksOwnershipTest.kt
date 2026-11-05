package dev.droidtop.runtime.windows.utils

import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.runtime.windows.utils.SteamworksOwnership.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamworksOwnershipTest {
    @Test
    fun `a flagged game is never checked or warned about, whatever Steam says now`() {
        assertEquals(Verdict.FLAGGED, SteamworksOwnership.verdict(flagged = true, signedIn = false, holding = null))
        assertEquals(Verdict.FLAGGED, SteamworksOwnership.verdict(flagged = true, signedIn = true, holding = StoreHolding.NOT_OWNED))
        assertFalse(SteamworksOwnership.warns(Verdict.FLAGGED))
        assertFalse(SteamworksOwnership.flagsNow(Verdict.FLAGGED))
    }

    @Test
    fun `the account listing the app flags it and does not warn`() {
        assertEquals(Verdict.ON_ACCOUNT, SteamworksOwnership.verdict(false, true, StoreHolding.OWNED))
        assertEquals(Verdict.ON_ACCOUNT, SteamworksOwnership.verdict(false, true, StoreHolding.FAMILY))
        assertTrue(SteamworksOwnership.flagsNow(Verdict.ON_ACCOUNT))
        assertFalse(SteamworksOwnership.warns(Verdict.ON_ACCOUNT))
    }

    @Test
    fun `an unflagged game the account doesn't list, or no longer holds, is warned about`() {
        assertEquals(Verdict.NOT_FOUND, SteamworksOwnership.verdict(false, true, null))
        assertEquals(Verdict.NOT_FOUND, SteamworksOwnership.verdict(false, true, StoreHolding.NOT_OWNED))
        assertTrue(SteamworksOwnership.warns(Verdict.NOT_FOUND))
        assertFalse(SteamworksOwnership.flagsNow(Verdict.NOT_FOUND))
    }

    @Test
    fun `an unflagged game with nobody signed in is warned about`() {
        assertEquals(Verdict.NOT_SIGNED_IN, SteamworksOwnership.verdict(false, false, null))
        assertTrue(SteamworksOwnership.warns(Verdict.NOT_SIGNED_IN))
    }

    @Test
    fun `the warning never says the game won't start`() {
        listOf(Verdict.NOT_FOUND, Verdict.NOT_SIGNED_IN).forEach { verdict ->
            assertTrue(SteamworksOwnership.warning(verdict, 440).endsWith("It still starts."))
        }
    }
}

package dev.droidtop.runtime.windows.utils

import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.runtime.windows.utils.SteamworksOwnership.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamworksOwnershipTest {
    @Test
    fun `a game the account holds is not warned about`() {
        assertEquals(Verdict.ON_ACCOUNT, SteamworksOwnership.verdict(true, StoreHolding.OWNED, confirmed = false))
        assertEquals(Verdict.ON_ACCOUNT, SteamworksOwnership.verdict(true, StoreHolding.FAMILY, confirmed = false))
        assertFalse(SteamworksOwnership.warns(Verdict.ON_ACCOUNT))
    }

    @Test
    fun `a game the account doesn't list, or no longer holds, is warned about`() {
        assertEquals(Verdict.NOT_FOUND, SteamworksOwnership.verdict(true, null, confirmed = false))
        assertEquals(Verdict.NOT_FOUND, SteamworksOwnership.verdict(true, StoreHolding.NOT_OWNED, confirmed = false))
        assertTrue(SteamworksOwnership.warns(Verdict.NOT_FOUND))
    }

    @Test
    fun `nobody signed in is warned about, since nothing could be checked`() {
        assertEquals(Verdict.NOT_SIGNED_IN, SteamworksOwnership.verdict(false, null, confirmed = false))
        assertTrue(SteamworksOwnership.warns(Verdict.NOT_SIGNED_IN))
    }

    @Test
    fun `yes I own this game silences the warning`() {
        assertEquals(Verdict.CONFIRMED, SteamworksOwnership.verdict(true, null, confirmed = true))
        assertEquals(Verdict.CONFIRMED, SteamworksOwnership.verdict(false, null, confirmed = true))
        assertFalse(SteamworksOwnership.warns(Verdict.CONFIRMED))
    }

    @Test
    fun `the warning never says the game won't start`() {
        listOf(Verdict.NOT_FOUND, Verdict.NOT_SIGNED_IN).forEach { verdict ->
            assertTrue(SteamworksOwnership.warning(verdict, 440).endsWith("It still starts."))
        }
    }
}

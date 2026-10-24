package dev.droidtop.library.debrid

import org.junit.Assert.assertEquals
import org.junit.Test

/** The person's order of their debrid accounts, which settles a tie between accounts a job costs the same (Droidtop/tracker#638). */
class DebridAccountsTest {
    @Test
    fun theStoredOrderComesFirstAndAServiceItDoesNotNameYetFollows() {
        assertEquals(listOf("torbox", "realdebrid"), DebridAccounts.ordered(null))
        assertEquals(listOf("realdebrid", "torbox"), DebridAccounts.ordered("realdebrid"))
        assertEquals("an unknown or repeated id is dropped", listOf("realdebrid", "torbox"), DebridAccounts.ordered("gone, realdebrid,realdebrid"))
    }

    @Test
    fun anAccountMovesOnePlaceAndNotPastTheEnds() {
        val order = listOf("torbox", "realdebrid")
        assertEquals(listOf("realdebrid", "torbox"), DebridAccounts.moved(order, "realdebrid", earlier = true))
        assertEquals(order, DebridAccounts.moved(order, "torbox", earlier = true))
        assertEquals(order, DebridAccounts.moved(order, "realdebrid", earlier = false))
        assertEquals(order, DebridAccounts.moved(order, "gone", earlier = true))
    }
}

package dev.droidtop.library.credentials

import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Keys and accounts" page's table (Droidtop/tracker#12). */
class CredentialServicesTest {
    @Test
    fun `every key the store holds is listed under exactly one service`() {
        val listed = CredentialServices.ALL.flatMap { it.keys }
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(CredentialStore.KEYS, listed.toSet())
    }

    @Test
    fun `a service is held when any of its keys is set`() {
        val set = mapOf(CredentialStore.SCREENSCRAPER_USER to "me", CredentialStore.TORBOX_API_KEY to "  ")
        val held = CredentialServices.held { set[it].orEmpty() }
        assertEquals(listOf("screenscraper"), held.map { it.id })
    }
}

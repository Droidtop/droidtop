package dev.droidtop.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Social says what it is for while nothing is connected (Droidtop/tracker#504). */
class SocialCatalogTest {
    @Test
    fun `with no friends the accounts heading is the page's purpose, and with some it is just Accounts`() {
        assertEquals("Connect an account to see friends and messages from Steam and your apps", SocialCatalog.accountsTitle(hasContacts = false))
        assertEquals("Accounts", SocialCatalog.accountsTitle(hasContacts = true))
    }
}

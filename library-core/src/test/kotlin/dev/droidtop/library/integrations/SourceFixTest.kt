package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.PluginWebAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/** A failed source's row runs the one action that fixes it (Droidtop/tracker#746). */
class SourceFixTest {
    @Test
    fun `a site that wants the person in it opens that site`() {
        assertEquals(SourceFix.SIGN_IN, SourceFix.of(PluginWebAccount.Need.SIGN_IN, siteKnown = true, permissionOff = false, needsRuntime = false))
        assertEquals(SourceFix.OPEN_SITE, SourceFix.of(PluginWebAccount.Need.BROWSER_CHECK, siteKnown = true, permissionOff = false, needsRuntime = false))
    }

    @Test
    fun `a site droidtop does not know falls through to the next fix`() {
        assertEquals(SourceFix.TRY_AGAIN, SourceFix.of(PluginWebAccount.Need.SIGN_IN, siteKnown = false, permissionOff = false, needsRuntime = false))
        assertEquals(SourceFix.OPEN_PAGE, SourceFix.of(PluginWebAccount.Need.SIGN_IN, siteKnown = false, permissionOff = true, needsRuntime = false))
    }

    @Test
    fun `a refused permission opens the page, a missing runtime gets it, anything else tries again`() {
        assertEquals(SourceFix.OPEN_PAGE, SourceFix.of(null, siteKnown = false, permissionOff = true, needsRuntime = true))
        assertEquals(SourceFix.GET_RUNTIME, SourceFix.of(null, siteKnown = false, permissionOff = false, needsRuntime = true))
        assertEquals(SourceFix.TRY_AGAIN, SourceFix.of(null, siteKnown = false, permissionOff = false, needsRuntime = false))
    }

    @Test
    fun `several failures fold into one row that counts them`() {
        assertEquals("1 source needs attention", SourceFix.foldedTitle(1))
        assertEquals("3 sources need attention", SourceFix.foldedTitle(3))
    }
}

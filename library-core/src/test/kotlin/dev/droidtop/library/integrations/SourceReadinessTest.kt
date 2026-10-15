package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which source plugins can answer a search, and how the ones that cannot are told (docs/SPEC.md 12a "One search", Droidtop/tracker#485). */
class SourceReadinessTest {
    private fun of(
        trust: PluginTrustState = PluginTrustState.APPROVED,
        enabled: Boolean = true,
        disabledReason: String? = null,
        point: GrantState = GrantState.GRANTED,
        label: String = "Nyaa",
    ) = UnavailableSource.of(label, label.lowercase(), trust, enabled, disabledReason, point)

    @Test
    fun `an approved, enabled plugin allowed to give games can answer`() {
        assertNull(of())
    }

    @Test
    fun `a plugin approved as a whole but not yet allowed to give games waits for approval instead of failing`() {
        val waiting = of(point = GrantState.ASK)
        assertNotNull(waiting)
        assertTrue(waiting!!.waitingForApproval)
        assertEquals("is waiting for your approval", waiting.reason)
    }

    @Test
    fun `a plugin not yet approved waits for approval`() {
        assertTrue(of(trust = PluginTrustState.PENDING)!!.waitingForApproval)
    }

    @Test
    fun `a refused or switched off plugin is a row of its own, not a waiting one`() {
        assertFalse(of(point = GrantState.DENIED)!!.waitingForApproval)
        assertEquals("was not allowed to give games", of(point = GrantState.DENIED)!!.reason)
        assertEquals("was denied", of(trust = PluginTrustState.DENIED)!!.reason)
        assertEquals("is turned off", of(enabled = false)!!.reason)
        assertEquals("is disabled: crashed", of(disabledReason = "crashed")!!.reason)
        assertFalse(of(enabled = false, point = GrantState.ASK)!!.waitingForApproval)
    }

    @Test
    fun `only a source missing just the point is asked about in place`() {
        assertTrue(of(point = GrantState.ASK)!!.askable)
        // Not approved yet: the whole plugin waits, which its page answers, not the point sheet.
        assertFalse(of(trust = PluginTrustState.PENDING, point = GrantState.ASK)!!.askable)
        assertFalse(of(point = GrantState.DENIED)!!.askable)
        assertFalse(of(enabled = false, point = GrantState.ASK)!!.askable)
    }

    @Test
    fun `all the sources waiting are one row, named by count`() {
        val one = listOf(of(point = GrantState.ASK)!!)
        assertEquals("Nyaa is waiting for your approval", UnavailableSource.waitingTitle(one))
        assertEquals("Open it to approve", UnavailableSource.waitingSubtitle(one))
        val three = listOf("A", "B", "C").map { of(point = GrantState.ASK, label = it)!! }
        assertEquals("3 sources are waiting for your approval", UnavailableSource.waitingTitle(three))
        assertEquals("Open to approve them", UnavailableSource.waitingSubtitle(three))
    }
}

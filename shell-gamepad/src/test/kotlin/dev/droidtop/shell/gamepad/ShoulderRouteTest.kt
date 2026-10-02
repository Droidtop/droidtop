package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Who L1/R1 belong to (docs/SPEC.md 7j, "Gaming controls"): the page's tab
 * strip when it has claimed them; otherwise they do not switch destinations.
 */
class ShoulderRouteTest {

    @Test
    fun `a page with no strip leaves shoulders unhandled`() {
        assertEquals(ShoulderRoute.NONE, shoulderRoute(stripOwned = false, detailOpen = false))
    }

    @Test
    fun `a page that owns a strip takes the shoulders`() {
        assertEquals(ShoulderRoute.STRIP, shoulderRoute(stripOwned = true, detailOpen = false))
    }

    @Test
    fun `a game's detail takes the shoulders from both`() {
        assertEquals(ShoulderRoute.NONE, shoulderRoute(stripOwned = false, detailOpen = true))
        assertEquals(ShoulderRoute.NONE, shoulderRoute(stripOwned = true, detailOpen = true))
    }

    @Test
    fun `a page that is leaving cannot release the strip of the page that arrived`() {
        val registry = ShoulderStripRegistry()
        val leaving = ShoulderStrip { }
        val arriving = ShoulderStrip { }
        registry.claim(leaving)
        registry.claim(arriving)
        registry.release(leaving)
        assertSame(arriving, registry.current)
        registry.release(arriving)
        assertNull(registry.current)
    }
}

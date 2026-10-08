package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one list of places, and where a link to one opens (Droidtop/tracker#346). */
class PlaceTest {

    @Test
    fun `every place is found by its screen id, nothing else is`() {
        Place.entries.forEach { assertEquals(it, Place.byScreenId(it.screenId)) }
        assertEquals(Place.DOWNLOADS, Place.byScreenId("plugin_jobs"))
        assertNull(Place.byScreenId("containers"))
        assertNull(Place.byScreenId(null))
    }

    @Test
    fun `Kiosk and Kid hide every place`() {
        assertEquals(Place.entries, Place.visible(UiMode.FULL))
        assertTrue(Place.visible(UiMode.KIOSK).isEmpty())
        assertTrue(Place.visible(UiMode.KID).isEmpty())
    }

    @Test
    fun `a link opens in Gaming only while Gaming is the mode in use and on`() {
        val all = setOf(Mode.GAMING, Mode.DESKTOP, Mode.LAUNCHER)
        assertTrue(Place.opensInGaming(Mode.GAMING.id, all))
        assertFalse(Place.opensInGaming(Mode.GAMING.id, setOf(Mode.DESKTOP, Mode.LAUNCHER)))
        assertFalse(Place.opensInGaming(Mode.DESKTOP.id, all))
        assertFalse(Place.opensInGaming(Mode.LAUNCHER.id, all))
        assertFalse(Place.opensInGaming(null, all))
    }
}

package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** [EsDeControllers.byShortName]: a known short name finds its controller; anything else is the Unknown Controller. */
class EsDeControllersLookupTest {
    @Test
    fun `a known short name finds its controller`() {
        assertEquals("Xbox Kinect", EsDeControllers.byShortName("xbox_kinect").displayName)
        assertEquals("Wii Remote (Nintendo)", EsDeControllers.byShortName("wii_remote_nintendo").displayName)
    }

    @Test
    fun `an unrecognised or missing short name is the unknown controller`() {
        assertEquals("unknown", EsDeControllers.byShortName("nonesuch").shortName)
        assertEquals("unknown", EsDeControllers.byShortName(null).shortName)
        assertEquals("Unknown Controller", EsDeControllers.byShortName("").displayName)
    }
}

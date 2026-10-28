package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#233: a component counts as in use whenever anything might name it, so Remove never takes one a game needs. */
class WineInstalledComponentsTest {

    @Test
    fun `a build no prefix or game names is free to remove`() {
        assertFalse(WineInstalledComponents.inUse("2.4.1", "DXVK-2.4.1-0", setOf("proton-9.0-arm64ec", "1.10.3", "turnip25.1.0")))
    }

    @Test
    fun `a build named by its store key is in use`() {
        assertTrue(WineInstalledComponents.inUse("ge-proton10-4", "Proton-ge-proton10-4-0", setOf("proton-ge-proton10-4-0")))
    }

    @Test
    fun `a value that carries the build's name is in use, whatever the case`() {
        assertTrue(WineInstalledComponents.inUse("2.4.1", "DXVK-2.4.1-0", setOf("dxvk-2.4.1-async")))
        assertTrue(WineInstalledComponents.inUse("Turnip25.1.0", "Turnip-Turnip25.1.0-1", setOf("turnip25.1.0")))
    }

    @Test
    fun `a build with no name is kept`() {
        assertTrue(WineInstalledComponents.inUse("", "DXVK--0", emptySet()))
    }

    @Test
    fun `bundled and in-use builds cannot be removed`() {
        val free = WineInstalledComponents.Component("DXVK", "2.4.1", "DXVK-2.4.1-0", 1L, inUse = false, bundled = false)
        assertTrue(free.removable)
        assertFalse(free.copy(inUse = true).removable)
        assertFalse(free.copy(bundled = true).removable)
    }

    @Test
    fun `an ARM device is offered every build`() {
        assertNull(WineOptionPlan.notHereReason(x86Host = false, wine = "proton-9.0-arm64ec"))
        assertNull(WineOptionPlan.notHereReason(x86Host = false, wine = "proton-9.0-x86_64"))
    }

    @Test
    fun `an x86_64 device says why an ARM or pre-10 build is not offered`() {
        assertEquals("An ARM (arm64ec) build; this device's CPU is x86_64", WineOptionPlan.notHereReason(true, "proton-10.0-4-arm64ec"))
        assertEquals("Made for Box64 on ARM; an x86_64 device runs Wine builds from 10 on", WineOptionPlan.notHereReason(true, "proton-9.0-x86_64"))
        assertNull(WineOptionPlan.notHereReason(true, "proton-10.0-4-x86_64-1"))
    }
}

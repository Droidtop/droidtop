package dev.droidtop.runtime.windows

import org.junit.Assert.assertFalse
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
}

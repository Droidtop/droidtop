package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Droidtop/tracker#786 S9: the companion's paired Bluetooth list, connected devices first. */
class PairedDevicesTest {
    @Test fun `connected devices come first, then by name`() {
        val list = listOf(
            PairedDevice("zeta buds", connected = false),
            PairedDevice("Alpha pad", connected = null),
            PairedDevice("Speaker", connected = true),
            PairedDevice("beta keyboard", connected = false),
        )
        assertEquals(listOf("Speaker", "Alpha pad", "beta keyboard", "zeta buds"), pairedInOrder(list).map { it.name })
    }
}

package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Test

/** The command line and exit status over adb's shell stream (docs/SPEC.md "Wireless debugging"). */
class WirelessDebuggingOpsTest {
    @Test
    fun `an argument is one single-quoted word, quotes inside it included`() {
        assertEquals("'device_config'", WirelessDebuggingOps.quote("device_config"))
        assertEquals("'it'\\''s'", WirelessDebuggingOps.quote("it's"))
    }

    @Test
    fun `the status is read from its own line, and a stream cut short reads as failed`() {
        val ok = WirelessDebuggingOps.parse("2147483647\n${WirelessDebuggingOps.EXIT_MARK}0\n")
        assertEquals(0, ok.exit)
        assertEquals("2147483647\n", ok.stdout)
        assertEquals(1, WirelessDebuggingOps.parse("Error\n${WirelessDebuggingOps.EXIT_MARK}1").exit)
        assertEquals(-1, WirelessDebuggingOps.parse("partial").exit)
    }
}

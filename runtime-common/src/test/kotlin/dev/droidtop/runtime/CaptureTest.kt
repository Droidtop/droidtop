package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one screenshot entry point (Capture.kt, Droidtop/tracker#414 slice C15). */
class CaptureTest {
    @Test fun `the helper app takes the main screen, the accessibility service any screen`() {
        assertEquals(Capture.Route.PROVIDER, Capture.route(providerShell = true, displayId = 0, accessibilityReady = true))
        assertEquals(Capture.Route.ACCESSIBILITY, Capture.route(providerShell = false, displayId = 0, accessibilityReady = true))
        // screencap -d wants a physical display id apps are not given: another screen goes through the service.
        assertEquals(Capture.Route.ACCESSIBILITY, Capture.route(providerShell = true, displayId = 2, accessibilityReady = true))
        assertEquals(Capture.Route.NONE, Capture.route(providerShell = true, displayId = 2, accessibilityReady = false))
        assertEquals(Capture.Route.NONE, Capture.route(providerShell = false, displayId = 0, accessibilityReady = false))
    }

    @Test fun `without a route the line says what would make it work`() {
        assertTrue(Capture.noRouteMessage(0, providerShell = false).contains("accessibility service"))
        assertTrue(Capture.noRouteMessage(2, providerShell = true).startsWith("Turn on droidtop's accessibility service"))
    }
}

package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/** The device-setup items' pure parts (docs/SPEC.md "Device setup", Droidtop/tracker#816). */
class DeviceSetupTest {
    private val own = "dev.droidtop.app/dev.droidtop.app.TypingAccessibilityService"
    private val vendor = "com.retroid.gameassistant/.Service"

    @Test
    fun `turning on keeps every service already on Android's list, and adds droidtop once`() {
        assertEquals("$vendor:$own", DeviceSetup.withService(vendor, own))
        assertEquals("$vendor:$own", DeviceSetup.withService("$vendor:$own", own))
        assertEquals(own, DeviceSetup.withService(null, own))
        assertEquals(own, DeviceSetup.withService("", own))
    }

    @Test
    fun `undoing takes only droidtop off the list`() {
        assertEquals(vendor, DeviceSetup.withoutService("$vendor:$own", own))
        assertEquals("", DeviceSetup.withoutService(own, own))
        assertEquals(vendor, DeviceSetup.withoutService(vendor, own))
    }

    @Test
    fun `the restricted-settings mode found before is read from appops' own line`() {
        assertEquals("deny", DeviceSetup.opModeName("ACCESS_RESTRICTED_SETTINGS: deny; time=+2d1h ago\n"))
        assertEquals("allow", DeviceSetup.opModeName("ACCESS_RESTRICTED_SETTINGS: allow"))
        assertEquals("default", DeviceSetup.opModeName("No operations."))
        assertEquals("default", DeviceSetup.opModeName(null))
        assertEquals("default", DeviceSetup.opModeName("ACCESS_RESTRICTED_SETTINGS: strange"))
    }
}

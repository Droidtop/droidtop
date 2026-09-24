package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorProvisioningTest {
    @Test
    fun `debian plus sway installs sway with apt and starts sway`() {
        val plan = CompositorProvisioning.plan("debian", "sway")!!
        assertTrue(plan.installCommand.contains("apt-get install") && plan.installCommand.contains(" sway "))
        assertEquals("sway", plan.compositorCommand)
    }

    @Test
    fun `alpine plus sway installs sway with apk and starts sway`() {
        val plan = CompositorProvisioning.plan("alpine", "sway")!!
        assertTrue(plan.installCommand.contains("apk add") && plan.installCommand.contains(" sway "))
        assertEquals("sway", plan.compositorCommand)
    }

    @Test
    fun `alpine plus labwc starts the labwc it installed, not sway`() {
        val plan = CompositorProvisioning.plan("alpine", "labwc")!!
        assertTrue(plan.installCommand.contains("apk add") && plan.installCommand.contains(" labwc "))
        assertEquals("labwc", plan.compositorCommand)
    }

    @Test
    fun `unsupported combinations return null instead of a guessed plan`() {
        assertNull(CompositorProvisioning.plan("alpine", "hyprland"))
        assertNull(CompositorProvisioning.plan("fedora", "sway"))
        assertNull(CompositorProvisioning.plan("debian", "labwc"))
    }

    @Test
    fun `a headless compositor needs no seat daemon, so none is installed`() {
        // wlroots only opens a session for its drm and libinput backends;
        // the compositor runs headless (ContainerLayout.compositorEnvironment).
        for ((os, de) in listOf("debian" to "sway", "alpine" to "sway", "alpine" to "labwc")) {
            assertFalse(CompositorProvisioning.plan(os, de)!!.installCommand.contains("seatd"))
        }
    }

    @Test
    fun `debian refuses service starts before installing`() {
        // Debian's policy-rc.d contract: exit 101 means "do not start".
        val install = CompositorProvisioning.plan("debian", "sway")!!.installCommand
        assertTrue(install.indexOf("policy-rc.d") in 0 until install.indexOf("apt-get"))
        assertTrue(install.contains("exit 101"))
    }

    @Test
    fun `every provisionable combination installs the terminal ContainerTerminal launches`() {
        // One constant names both, so a primary container never provisions
        // one terminal and tries to run another (docs/SPEC.md 3d).
        for ((os, de) in listOf("debian" to "sway", "alpine" to "sway", "alpine" to "labwc")) {
            val words = CompositorProvisioning.plan(os, de)!!.installCommand.split(" ")
            assertTrue("$os/$de must install ${ContainerTerminal.PACKAGE}", words.contains(ContainerTerminal.PACKAGE))
        }
    }
}

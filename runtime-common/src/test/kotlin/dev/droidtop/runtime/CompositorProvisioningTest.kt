package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorProvisioningTest {
    @Test
    fun `debian plus sway resolves to a real apt install command`() {
        val command = CompositorProvisioning.installCommand("debian", "sway")
        assertTrue(command != null && command.contains("apt-get install") && command.contains("sway"))
    }

    @Test
    fun `alpine plus sway resolves to a real apk install command`() {
        val command = CompositorProvisioning.installCommand("alpine", "sway")
        assertTrue(command != null && command.contains("apk add") && command.contains("sway"))
    }

    @Test
    fun `alpine plus labwc resolves to a real apk install command`() {
        val command = CompositorProvisioning.installCommand("alpine", "labwc")
        assertTrue(command != null && command.contains("apk add") && command.contains("labwc"))
    }

    @Test
    fun `unsupported combinations return null instead of a guessed command`() {
        assertNull(CompositorProvisioning.installCommand("alpine", "hyprland"))
        assertNull(CompositorProvisioning.installCommand("fedora", "sway"))
        assertNull(CompositorProvisioning.installCommand("debian", "labwc"))
    }

    @Test
    fun `every catalog PRIMARY entry that is provisionable actually resolves`() {
        // Real regression guard: known-image-repositories.json's own
        // per-entry notes claim which combinations are provisionable --
        // this catches the two silently drifting apart (e.g. a catalog
        // entry added for a compositor CompositorProvisioning never
        // learned, or vice versa).
        //
        // The trailing terminal package comes from ContainerTerminal rather
        // than being spelled out again: provisioning installs it and
        // ContainerTerminal launches it, and one constant naming both is
        // what stops a primary container provisioning one terminal and
        // trying to run another (docs/SPEC.md 3d).
        val terminal = ContainerTerminal.PACKAGE
        assertEquals(
            "apt-get update && apt-get install -y --no-install-recommends sway seatd xwayland " + terminal,
            CompositorProvisioning.installCommand("debian", "sway"),
        )
        assertEquals(
            "apk add --no-cache sway seatd xwayland " + terminal,
            CompositorProvisioning.installCommand("alpine", "sway"),
        )
        assertEquals(
            "apk add --no-cache labwc seatd " + terminal,
            CompositorProvisioning.installCommand("alpine", "labwc"),
        )
    }
}

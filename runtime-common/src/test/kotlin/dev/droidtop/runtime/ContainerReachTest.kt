package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** A container's "What it can reach" lines (docs/SPEC.md 3d; Droidtop/tracker#787 slice 1). */
class ContainerReachTest {
    private fun lines(
        mounts: EnvironmentMounts = EnvironmentMounts(),
        sockets: ContainerSockets = ContainerSockets(),
        microphone: Boolean = false,
        devices: Int = 0,
        servesVpn: Boolean = false,
        network: Boolean = true,
    ) = ContainerReach.lines(ContainerReach.Settings(mounts, sockets, microphone, devices, servesVpn, network)).associate { it.kind to it.what }

    @Test
    fun `a new container has its own home, shared storage, windows, sound, no microphone, no devices, the device's network`() {
        assertEquals(
            mapOf(
                "Files" to "Its own home, the device's shared storage",
                "Screen" to "Shows its windows on the desktop",
                "Sound" to "Plays sound; no microphone",
                "USB devices" to "None",
                "Network" to "This device's network, as any app has",
            ),
            lines(),
        )
    }

    @Test
    fun `each row's setting changes its line`() {
        val shared = lines(mounts = EnvironmentMounts(home = HomeChoice.Shared, folders = listOf(ExtraMount("/storage/emulated/0/Music", "Music"))))
        assertEquals("The shared home folder, the device's shared storage, and Music", shared["Files"])
        val chosen = lines(
            mounts = EnvironmentMounts(
                home = HomeChoice.Chosen("/storage/emulated/0/Work/"),
                folders = listOf(ExtraMount("/a", "Photos"), ExtraMount("/b", "Code")),
            ),
        )
        assertEquals("Home in Work, the device's shared storage, and 2 folders (Photos, Code)", chosen["Files"])
        assertEquals("No windows on the desktop", lines(sockets = ContainerSockets(waylandShared = false))["Screen"])
        assertEquals("No sound", lines(sockets = ContainerSockets(audioShared = false), microphone = true)["Sound"])
        assertEquals("Plays sound and hears the microphone", lines(microphone = true)["Sound"])
        assertEquals("1 device", lines(devices = 1)["USB devices"])
        assertEquals("3 devices", lines(devices = 3)["USB devices"])
        assertEquals("This device's network, as any app has; it carries the VPN for the apps chosen", lines(servesVpn = true)["Network"])
    }

    @Test
    fun `a container with its network taken away says so, VPN or not`() {
        assertEquals("None at all", lines(network = false)["Network"])
        assertEquals("None at all", lines(network = false, servesVpn = true)["Network"])
    }
}

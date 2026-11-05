package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.ContainerSockets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rooted backend's pure decisions (Droidtop/tracker#731): its config text, its delete guard, its sockets file. */
class RootBackendTest {
    private val rootfs = "/data/user/0/dev.droidtop.app/files/droidspaces/rootfs/box"

    @Test
    fun `the config is droidspaces' own key=value lines`() {
        val text = DroidSpacesContainerConfig(
            name = "box",
            rootfsPath = rootfs,
            bindMounts = listOf("/a" to "/run/a", "/b" to "/run/b"),
            envFilePath = "/cfg/box.env",
        ).toConfigText().lines()
        assertTrue("name=box" in text)
        assertTrue("hostname=box" in text)
        assertTrue("rootfs_path=$rootfs" in text)
        assertTrue("bind_mounts=/a:/run/a,/b:/run/b" in text)
        assertTrue("env_file=/cfg/box.env" in text)
        assertTrue("volatile_mode=0" in text)
        assertTrue("enable_pulseaudio=1" in text)
        assertTrue("enable_gpu_mode=1" in text)
        assertTrue("foreground=0" in text)
        // The host's network unless taken away: droidspaces' default stands, so no net_mode line.
        assertFalse(text.any { it.startsWith("net_mode") })
    }

    @Test
    fun `no network is net_mode none, no binds is no bind line`() {
        val text = DroidSpacesContainerConfig(name = "box", rootfsPath = rootfs, noNetwork = true, enablePulseAudio = false)
            .toConfigText().lines()
        assertTrue("net_mode=none" in text)
        assertTrue("enable_pulseaudio=0" in text)
        assertFalse(text.any { it.startsWith("bind_mounts") || it.startsWith("env_file") })
    }

    @Test
    fun `a shallow path is never deleted`() {
        assertNotNull(RootfsDelete.tooShallow("/"))
        assertNotNull(RootfsDelete.tooShallow("/data"))
        assertNotNull(RootfsDelete.tooShallow("/data/user"))
        assertNull(RootfsDelete.tooShallow(rootfs))
    }

    @Test
    fun `a mount at or under the rootfs refuses the delete, a neighbour's does not`() {
        val mounts = """
            /dev/block/dm-5 /data ext4 rw 0 0
            tmpfs $rootfs-old/tmp tmpfs rw 0 0
        """.trimIndent()
        assertNull(RootfsDelete.mountedUnder(rootfs, mounts))
        assertNotNull(RootfsDelete.mountedUnder(rootfs, "$mounts\n/dev/fuse $rootfs/run/droidtop-app-storage fuse rw 0 0"))
        assertNotNull(RootfsDelete.mountedUnder(rootfs, "tmpfs $rootfs tmpfs rw 0 0"))
        assertTrue(RootfsDelete.mountedUnder(rootfs, "x $rootfs/run/s y 0 0")!!.contains("$rootfs/run/s"))
        assertNull(RootfsDelete.mountedUnder(rootfs, ""))
    }

    @Test
    fun `the sockets file reads back what it wrote, and anything unclear stays on`() {
        val off = ContainerSockets(waylandShared = false, audioShared = true, clipboardShared = false)
        assertEquals(off, DroidSpacesRuntime.parseSockets(DroidSpacesRuntime.socketsText(off).lines()))
        assertEquals(ContainerSockets(), DroidSpacesRuntime.parseSockets(emptyList()))
        assertEquals(ContainerSockets(), DroidSpacesRuntime.parseSockets(listOf("wayland=no", "audio=", "clipboard=FALSE", "junk")))
    }
}

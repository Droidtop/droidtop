package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** The root commands of a droidspaces container's copy and backup (docs/SPEC.md 3d; Droidtop/tracker#758). */
class RootTarTest {
    private val rootfs = "/data/user/0/dev.droidtop.app/files/droidspaces/rootfs/droidtop-sibling-1"

    @Test
    fun `sockets in the tree become excludes, spelled as toybox names members`() {
        val found = "$rootfs/tmp/.X11-unix/X0\n$rootfs/run/dbus/system_bus_socket\n/elsewhere/sock\n\n"
        assertEquals(
            listOf("--exclude=./tmp/.X11-unix/X0", "--exclude=./run/dbus/system_bus_socket"),
            RootTar.socketExcludes(rootfs, found),
        )
        assertEquals(listOf("--exclude=./a"), RootTar.socketExcludes("$rootfs/", "$rootfs/a"))
        assertEquals(emptyList<String>(), RootTar.socketExcludes(rootfs, ""))
    }

    @Test
    fun `the commands are argv for Android's own tools, never a shell line`() {
        assertEquals(listOf("/system/bin/find", rootfs, "-xdev", "-type", "s"), RootTar.socketsCommand(rootfs))
        assertEquals(
            listOf("/system/bin/tar", "-cf", "-", "--exclude=./tmp/s", "-C", rootfs, "."),
            RootTar.exportCommand(rootfs, listOf("--exclude=./tmp/s")),
        )
        assertEquals(listOf("/system/bin/tar", "-xf", "-", "-C", "$rootfs.restore"), RootTar.importCommand("$rootfs.restore"))
        assertEquals(listOf("/system/bin/cp", "-a", rootfs, "$rootfs-copy"), RootTar.copyCommand(rootfs, "$rootfs-copy"))
    }
}

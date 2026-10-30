package dev.droidtop.sandboxhost

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The program tree's environment (Droidtop/tracker#584, piece 0), against the names and flags of vendor/sandbox's sbx.h. */
class SandboxHostTest {
    private val lib = File("/data/app/x/lib/arm64")

    @Test
    fun containIsTheLibrarysPreset() {
        // sbx.h: SBX_LOCK_CONTAIN = PATHOPS | INOTIFY | SOCKETS | SIGNALS | EXEC.
        assertEquals(0x1F00, SandboxHost.LOCK_CONTAIN)
        assertEquals(0x1F21, SandboxHost.LEARNING_FLAGS)
    }

    @Test
    fun aContainedTreeGetsTheBrokerTheHelperAndTheChildLibrary() {
        val env = SandboxHost.environment("/data/x/sbx/b.sock", "t0k", SandboxHost.LEARNING_FLAGS, lib, listOf("/dev/kgsl-3d0", "/dev/dri/renderD128"))
        assertEquals("/data/x/sbx/b.sock", env["SBX_BROKER"])
        assertEquals("t0k", env["SBX_TOKEN"])
        assertEquals("0x1f21", env["SBX_FLAGS"])
        assertEquals("/dev/kgsl-3d0:/dev/dri/renderD128", env["SBX_OWN_OPENS"])
        assertEquals("/data/app/x/lib/arm64/libsbx_exec.so", env["SBX_EXEC_HELPER"])
        assertEquals("/data/app/x/lib/arm64/libsbx_child.so", env["SBX_CHILD_LIBRARY"])
    }

    @Test
    fun withoutBrokeredExecThereIsNoHelper() {
        val env = SandboxHost.environment("/s", "t", SandboxHost.LOCK_LEARN, lib, emptyList())
        assertFalse("SBX_EXEC_HELPER" in env)
        assertFalse("SBX_OWN_OPENS" in env)
    }

    @Test
    fun theChildLibraryGoesFirstAndTheOtherPreloadsStay() {
        assertEquals(
            "/data/app/x/lib/arm64/libsbx_child.so:/a/libsysvshm.so:/b/libevshim.so",
            SandboxHost.withChildPreload("/a/libsysvshm.so /b/libevshim.so", lib),
        )
        assertEquals("/data/app/x/lib/arm64/libsbx_child.so", SandboxHost.withChildPreload(null, lib))
        assertEquals(
            "/data/app/x/lib/arm64/libsbx_child.so:/a/x.so",
            SandboxHost.withChildPreload("/data/app/x/lib/arm64/libsbx_child.so:/a/x.so", lib),
        )
    }
}

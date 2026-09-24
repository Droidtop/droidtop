package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContainerLayoutTest {
    private val plan = PrimaryProvisioning(installCommand = "false && true", compositorCommand = "sway")

    @Test
    fun `the boot script checks the install itself instead of trusting set -e`() {
        // `set -e` ignores a failure anywhere in an && list but the last
        // command; the script must not reach the marker after a failed
        // `apt-get update`.
        val script = ContainerLayout.primaryInitScript(plan)
        val guard = script.indexOf("if ! { false && true; }; then")
        val marker = script.indexOf("touch ${ContainerLayout.PROVISIONED_MARKER}")
        assertTrue(guard >= 0)
        assertTrue(marker > guard)
        assertTrue(script.substring(guard, marker).contains("exit 1"))
    }

    @Test
    fun `the compositor runs headless with software rendering, as the last thing the script does`() {
        val script = ContainerLayout.primaryInitScript(plan)
        assertTrue(script.contains("export WLR_BACKENDS=headless"))
        assertTrue(script.contains("export WLR_HEADLESS_OUTPUTS=1"))
        assertTrue(script.contains("export WLR_RENDERER=pixman"))
        assertTrue(script.contains("export XDG_RUNTIME_DIR=${ContainerLayout.SOCKET_DIR}"))
        assertEquals("exec sway", script.trimEnd().lines().last())
    }

    @Test
    fun `clients get the socket directory and name the compositor creates`() {
        assertEquals(ContainerLayout.SOCKET_DIR, ContainerLayout.clientEnvironment["XDG_RUNTIME_DIR"])
        assertEquals(ContainerLayout.WAYLAND_SOCKET_NAME, ContainerLayout.clientEnvironment["WAYLAND_DISPLAY"])
    }

    @Test
    fun `app storage paths map under the fixed in-container directory`() {
        val storage = File("/data/user/0/dev.droidtop.app/files")
        assertEquals(
            "${ContainerLayout.APP_STORAGE_DIR}/imagefs/home",
            ContainerLayout.hostStorageToContainerPath(storage, File(storage, "imagefs/home")),
        )
        assertEquals(ContainerLayout.APP_STORAGE_DIR, ContainerLayout.hostStorageToContainerPath(storage, storage))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a path outside app storage is a caller bug`() {
        ContainerLayout.hostStorageToContainerPath(File("/data/user/0/dev.droidtop.app/files"), File("/sdcard/x"))
    }
}

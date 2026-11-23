package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SharedStorageTest {
    private val volumes = listOf(
        SharedVolume(SharedVolume.PRIMARY, File("/storage/emulated/0")),
        SharedVolume("1234-ABCD", File("/storage/1234-ABCD")),
    )

    @Test
    fun `each volume is bound under its own name`() {
        assertEquals(
            listOf(
                "/storage/emulated/0" to "${ContainerLayout.SHARED_STORAGE_DIR}/primary",
                "/storage/1234-ABCD" to "${ContainerLayout.SHARED_STORAGE_DIR}/1234-ABCD",
            ),
            ContainerLayout.sharedStorageBinds(volumes),
        )
    }

    @Test
    fun `a file on a volume has a path inside every container`() {
        assertEquals(
            "${ContainerLayout.SHARED_STORAGE_DIR}/primary/Download/setup.exe",
            ContainerLayout.sharedStorageToContainerPath(volumes, File("/storage/emulated/0/Download/setup.exe")),
        )
        assertEquals(
            "${ContainerLayout.SHARED_STORAGE_DIR}/1234-ABCD/game.AppImage",
            ContainerLayout.sharedStorageToContainerPath(volumes, File("/storage/1234-ABCD/game.AppImage")),
        )
    }

    @Test
    fun `a game file is found in app storage or on a card, through one mapping`() {
        val files = File("/data/user/0/dev.droidtop.app/files")
        // Bare Witness on the console's card: the Linux launch asked app storage only and failed.
        assertEquals(
            "${ContainerLayout.SHARED_STORAGE_DIR}/1234-ABCD/GameSync/Adult/renpy/BareWitness-0.5-pc/BareWitness.sh",
            ContainerLayout.toContainerPath(files, volumes, File("/storage/1234-ABCD/GameSync/Adult/renpy/BareWitness-0.5-pc/BareWitness.sh")),
        )
        assertEquals(
            "${ContainerLayout.APP_STORAGE_DIR}/linux-game-homes/x",
            ContainerLayout.toContainerPath(files, volumes, File(files, "linux-game-homes/x")),
        )
        assertNull(ContainerLayout.toContainerPath(files, volumes, File("/data/data/other.app/files/x.sh")))
    }

    @Test
    fun `a file on no volume has none`() {
        assertNull(ContainerLayout.sharedStorageToContainerPath(volumes, File("/data/data/other.app/files/x.deb")))
        assertNull(ContainerLayout.sharedStorageToContainerPath(volumes, File("/storage/emulated/10/x.deb")))
    }

    @Test
    fun `a volume root is found from the app's own directory on it`() {
        assertEquals(
            File("/storage/1234-ABCD"),
            SharedVolume.volumeRoot(File("/storage/1234-ABCD/Android/data/dev.droidtop.app/files")),
        )
        assertEquals(SharedVolume.PRIMARY, SharedVolume.nameFor(File("/storage/emulated/0")))
        assertEquals("1234-ABCD", SharedVolume.nameFor(File("/storage/1234-ABCD")))
    }
}

package dev.droidtop.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/SPEC.md 3d "Home and folders" (Droidtop/tracker#555 D3): one description, read by both backends. */
class EnvironmentMountsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a mounts file from before homes keeps the container's own root and its folders`() {
        val legacy = "/storage/emulated/0/Music\tMusic\n/storage/ABCD-1234/Roms\tRoms\n"
        val read = EnvironmentMounts.decode(legacy)
        assertEquals(HomeChoice.Private, read.home)
        assertEquals(listOf(ExtraMount("/storage/emulated/0/Music", "Music"), ExtraMount("/storage/ABCD-1234/Roms", "Roms")), read.folders)
    }

    @Test
    fun `every home choice and the folders survive a write and a read`() {
        val folders = listOf(ExtraMount("/storage/emulated/0/Music", "Music"))
        for (home in listOf(HomeChoice.Shared, HomeChoice.Private, HomeChoice.Chosen("/storage/emulated/0/Linux home"))) {
            val mounts = EnvironmentMounts(home, folders)
            assertEquals(mounts, EnvironmentMounts.decode(EnvironmentMounts.encode(mounts)))
        }
    }

    @Test
    fun `an empty or unreadable file is a private home with no folders`() {
        assertEquals(EnvironmentMounts(), EnvironmentMounts.decode(""))
        assertEquals(EnvironmentMounts(), EnvironmentMounts.decode("garbage\n@home\tchosen\n\n"))
    }

    @Test
    fun `a new container shares the home`() {
        assertEquals(HomeChoice.Shared, EnvironmentMounts.NEW_CONTAINER.home)
    }

    @Test
    fun `the shared home is bound at root, made when first needed, before the folders`() {
        val shared = File(tmp.root, "files/desktop-home")
        val music = tmp.newFolder("Music")
        val binds = EnvironmentMounts(HomeChoice.Shared, listOf(ExtraMount(music.path, "Music"))).binds(shared)
        assertEquals(listOf(shared.absolutePath to "/root", music.path to "${ContainerLayout.EXTRA_MOUNTS_DIR}/Music"), binds)
        assertTrue(shared.isDirectory)
    }

    @Test
    fun `a private home binds nothing over root`() {
        val shared = File(tmp.root, "files/desktop-home")
        assertEquals(emptyList<Pair<String, String>>(), EnvironmentMounts(HomeChoice.Private).binds(shared))
    }

    @Test
    fun `a chosen home or a folder that is gone is left out, not a failed start`() {
        val shared = File(tmp.root, "files/desktop-home")
        val gone = File(tmp.root, "gone").path
        val mounts = EnvironmentMounts(HomeChoice.Chosen(gone), listOf(ExtraMount(gone, "Old")))
        assertEquals(emptyList<Pair<String, String>>(), mounts.binds(shared))
        val home = tmp.newFolder("Linux home")
        assertEquals(listOf(home.path to "/root"), EnvironmentMounts(HomeChoice.Chosen(home.path)).binds(shared))
    }

    @Test
    fun `the shared home sits in app storage, beside the image trees`() {
        assertEquals(File("/data/user/0/dev.droidtop.app/files/desktop-home"), ContainerLayout.sharedHomeDir(File("/data/user/0/dev.droidtop.app/files")))
    }
}

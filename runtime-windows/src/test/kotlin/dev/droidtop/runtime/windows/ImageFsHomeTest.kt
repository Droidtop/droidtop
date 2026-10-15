package dev.droidtop.runtime.windows

import dev.droidtop.runtime.SafeDelete
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Droidtop/tracker#249: the unfinished setup is cleared under the home the installer really uses, and nowhere else. */
class ImageFsHomeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun layout(): Triple<File, File, File> {
        val files = tmp.newFolder("files")
        val imageFs = File(files, "imagefs").apply { mkdirs() }
        val shared = File(files, "imagefs_shared/home").apply { mkdirs() }
        return Triple(files, imageFs, shared)
    }

    private fun link(at: File, to: File) {
        Files.createSymbolicLink(at.toPath(), to.toPath())
    }

    @Test
    fun `a real home inside the image is the image's own`() {
        val (_, imageFs, shared) = layout()
        val home = File(imageFs, "home").apply { mkdirs() }
        assertEquals(ImageFsHome.Root.Found(home), ImageFsHome.root(imageFs, shared))
    }

    @Test
    fun `a home not made yet is the image's own, with nothing in it to clear`() {
        val (_, imageFs, shared) = layout()
        assertEquals(ImageFsHome.Root.Found(File(imageFs, "home")), ImageFsHome.root(imageFs, shared))
    }

    @Test
    fun `the installer's link to the shared home is the shared home`() {
        val (_, imageFs, shared) = layout()
        link(File(imageFs, "home"), shared)
        assertEquals(ImageFsHome.Root.Found(shared.toPath().toRealPath().toFile()), ImageFsHome.root(imageFs, shared))
    }

    @Test
    fun `a home linked anywhere else is refused`() {
        val (files, imageFs, shared) = layout()
        val elsewhere = File(files, "elsewhere").apply { mkdirs() }
        link(File(imageFs, "home"), elsewhere)
        val root = ImageFsHome.root(imageFs, shared)
        assertTrue(root is ImageFsHome.Root.Refused)
        assertTrue((root as ImageFsHome.Root.Refused).why.contains("not the shared home"))
    }

    @Test
    fun `a dangling home link is refused`() {
        val (files, imageFs, shared) = layout()
        link(File(imageFs, "home"), File(files, "gone"))
        assertTrue(ImageFsHome.root(imageFs, shared) is ImageFsHome.Root.Refused)
    }

    @Test
    fun `the console's half-made prefix is cleared through the link, and its drive links are not followed`() {
        val (files, imageFs, shared) = layout()
        link(File(imageFs, "home"), shared)
        // What the fence must protect: a file outside the prefix that z: reaches.
        val outside = File(files, "games/keep.txt").apply { parentFile!!.mkdirs(); writeText("the person's game") }
        val prefix = File(shared, "xuser-1")
        val dosdevices = File(prefix, ".wine/dosdevices").apply { mkdirs() }
        link(File(dosdevices, "z:"), files)
        File(prefix, ".wine/drive_c/windows").apply { mkdirs(); File(this, "system.reg").writeText("x") }

        // Fenced by imagefs, as before: refused, because the prefix is really in imagefs_shared.
        assertTrue(SafeDelete.clearWithin(imageFs, File(imageFs, "home/xuser-1"))!!.contains("is not inside"))

        val root = (ImageFsHome.root(imageFs, shared) as ImageFsHome.Root.Found).dir
        assertNull(SafeDelete.clearWithin(root, File(root, "xuser-1")))
        assertFalse(prefix.exists())
        assertTrue(outside.isFile)
        assertTrue(shared.isDirectory)
    }
}

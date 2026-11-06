package dev.droidtop.runtime.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The one size-of-a-folder helper (Droidtop/tracker#766). */
class DirectorySizeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a folder is the bytes of every file under it`() {
        val root = temp.newFolder("game")
        File(root, "a.bin").writeBytes(ByteArray(10))
        File(root, "data/deep").mkdirs()
        File(root, "data/b.bin").writeBytes(ByteArray(20))
        File(root, "data/deep/c.bin").writeBytes(ByteArray(5))
        assertEquals(35L, root.sizeOnDisk())
        assertEquals(10L, File(root, "a.bin").sizeOnDisk())
        assertEquals(0L, File(root, "missing").sizeOnDisk())
    }

    @Test
    fun `a link back into the tree is not counted twice`() {
        val root = temp.newFolder("install")
        File(root, "game.exe").writeBytes(ByteArray(100))
        val link = File(root, "scriptinterpreter/rootdir")
        link.parentFile!!.mkdirs()
        val made = runCatching { Files.createSymbolicLink(link.toPath(), root.toPath()) }.isSuccess
        assertEquals(100L, root.sizeOnDisk())
        if (made) assertEquals(0L, link.sizeOnDisk())
    }
}

package dev.droidtop.stores

import dev.droidtop.stores.data.ItchUpload
import dev.droidtop.stores.itch.ItchStore
import dev.droidtop.stores.util.StoreFiles
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which itch.io upload an install takes, and the folder rules every store shares (docs/SPEC.md 7g, "Stores"). */
class ItchStoreTest {

    private fun upload(id: Long, windows: Boolean = false, linux: Boolean = false, android: Boolean = false, demo: Boolean = false) =
        ItchUpload(id, "file$id.zip", null, 1L, windows, linux, android, demo)

    @Test
    fun `a reinstall takes the upload the game had`() {
        val uploads = listOf(upload(1, windows = true), upload(2, linux = true))
        assertEquals(2L, ItchStore.chooseUpload(uploads, installedUploadId = 2)?.id)
    }

    @Test
    fun `a first install takes a Windows or Linux build that is not a demo`() {
        val uploads = listOf(upload(1, android = true), upload(2, windows = true, demo = true), upload(3, linux = true))
        assertEquals(3L, ItchStore.chooseUpload(uploads, installedUploadId = 0)?.id)
    }

    @Test
    fun `an upload id that is gone falls back to the first runnable build, and no upload is no choice`() {
        val uploads = listOf(upload(5, android = true), upload(6, windows = true))
        assertEquals(6L, ItchStore.chooseUpload(uploads, installedUploadId = 99)?.id)
        assertEquals(5L, ItchStore.chooseUpload(listOf(upload(5, android = true)), installedUploadId = 0)?.id)
        assertNull(ItchStore.chooseUpload(emptyList(), installedUploadId = 0))
    }

    @Test
    fun `a folder name keeps what every card's filesystem accepts`() {
        assertEquals("Doki Doki Literature Club", StoreFiles.folderName("Doki Doki Literature Club!", "x"))
        assertEquals("Baldurs Gate 3", StoreFiles.folderName("Baldur's Gate 3", "x"))
        assertEquals("What", StoreFiles.folderName("What?:*|<>\"", "x"))
        assertEquals("Version 1.0", StoreFiles.folderName("Version 1.0.", "x"))
        assertEquals("itch-42", StoreFiles.folderName("???", "itch-42"))
    }

    @Test
    fun `a new install never gets a folder that already holds files`() {
        val root = Files.createTempDirectory("store").toFile()
        assertEquals(File(root, "Cool Game"), StoreFiles.freshFolder(root, "Cool Game!", "x"))
        // An empty folder of that name is taken as it is.
        File(root, "Cool Game").mkdirs()
        assertEquals(File(root, "Cool Game"), StoreFiles.freshFolder(root, "Cool Game", "x"))
        // A folder with someone's files in it is passed over.
        File(root, "Cool Game/save.dat").writeText("theirs")
        File(root, "Cool Game (2)").apply { mkdirs() }.let { File(it, "a").writeText("a") }
        assertEquals(File(root, "Cool Game (3)"), StoreFiles.freshFolder(root, "Cool Game", "x"))
        root.deleteRecursively()
    }

    @Test
    fun `the change stamp moves when a file changes`() {
        val dir = Files.createTempDirectory("stamp").toFile()
        val file = File(dir, "stores.db").apply { writeText("a") }
        val before = StoreFiles.stamp(listOf(file, File(dir, "missing")))
        file.setLastModified(file.lastModified() + 10_000)
        assertNotEquals(before, StoreFiles.stamp(listOf(file, File(dir, "missing"))))
        dir.deleteRecursively()
    }
}

package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What "Unpack an installer" hands the unpacker, and where it puts the result (docs/SPEC.md 7c). */
class InnoExtractTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the build is the first ABI of the device that the catalog has one for`() {
        assertEquals("tools/innoextract-1.9-arm64-v8a", InnoExtract.catalogPath(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("tools/innoextract-1.9-x86_64", InnoExtract.catalogPath(arrayOf("x86_64", "x86", "arm64-v8a")))
        assertEquals("tools/innoextract-1.9-arm64-v8a", InnoExtract.catalogPath(arrayOf("armeabi-v7a", "arm64-v8a")))
        assertNull(InnoExtract.catalogPath(arrayOf("armeabi-v7a", "x86")))
    }

    @Test
    fun `the binary is started through the linker, and the installer is only an argument`() {
        val command = InnoExtract.command(File("/data/user/0/dev.droidtop.app/files/tools/innoextract-1.9-arm64-v8a"), listOf("--version"))
        assertEquals(
            listOf("/system/bin/linker64", "/data/user/0/dev.droidtop.app/files/tools/innoextract-1.9-arm64-v8a", "--version"),
            command,
        )
        assertEquals(
            listOf("--extract", "--output-dir", "/out/Game", "/dl/setup_game.exe"),
            InnoExtract.unpackArguments(File("/dl/setup_game.exe"), File("/out/Game")),
        )
    }

    @Test
    fun `the output folder is new, never one that exists`() {
        val parent = tmp.newFolder("Unpacked")
        assertEquals(File(parent, "setup_game"), InnoExtract.freshFolder(parent, "setup_game"))
        File(parent, "setup_game").mkdirs()
        assertEquals(File(parent, "setup_game 2"), InnoExtract.freshFolder(parent, "setup_game"))
        File(parent, "setup_game 2").mkdirs()
        assertEquals(File(parent, "setup_game 3"), InnoExtract.freshFolder(parent, "setup_game"))
    }

    @Test
    fun `characters a folder name cannot hold are replaced, and an empty name gets a default`() {
        val parent = tmp.newFolder("Unpacked")
        assertEquals("a b c", InnoExtract.freshFolder(parent, "a/b:c").name)
        assertEquals("Unpacked game", InnoExtract.freshFolder(parent, " ").name)
    }

    @Test
    fun `the line shown is the last one the progress bar drew`() {
        assertEquals("Extracting file 3 of 10", InnoExtract.lastLine("Extracting file 1 of 10\rExtracting file 2 of 10\rExtracting file 3 of 10\r\n\n"))
        assertEquals("", InnoExtract.lastLine("  \n"))
    }
}

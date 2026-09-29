package dev.droidtop.library.lutris

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-game half of an import, matched against a real folder on
 * disk: the longest trailing run of the script's path that exists under
 * the game folder, an executable that must be a regular .exe file, and
 * a match that canonical resolution cannot lead out of the game folder
 * (a symlink is not a way out).
 */
class LutrisImportResolveInFolderTest {

    private val temporaryDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryDirs.forEach { it.deleteRecursively() }
    }

    private fun dir(): File = Files.createTempDirectory("lutris").toFile().also { temporaryDirs += it }

    private fun plan(exePath: List<String>?, workingDir: List<String>? = null): LutrisImportPlan =
        LutrisImportPlan(exePath, null, workingDir, WinePrefixChanges(), emptyList(), emptyList())

    @Test
    fun `the longest trailing run of the script path that exists wins`() {
        val root = dir()
        File(root, "bin").mkdirs()
        File(root, "bin/Foo.exe").writeText("stub")
        val inFolder = LutrisImport.resolveInFolder(plan(listOf("drive_c", "GOG Games", "Foo", "bin", "Foo.exe")), root)
        assertEquals("bin/Foo.exe", inFolder.executable)
        assertTrue(inFolder.notImported.isEmpty())
    }

    @Test
    fun `the whole GAMEDIR-shaped path matches when the whole prefix is in the folder`() {
        val root = dir()
        File(root, "drive_c/GOG Games/Foo/bin").mkdirs()
        File(root, "drive_c/GOG Games/Foo/bin/Foo.exe").writeText("stub")
        val inFolder = LutrisImport.resolveInFolder(plan(listOf("drive_c", "GOG Games", "Foo", "bin", "Foo.exe")), root)
        assertEquals("drive_c/GOG Games/Foo/bin/Foo.exe", inFolder.executable)
    }

    @Test
    fun `the trailing run can be the file name alone`() {
        val root = dir()
        File(root, "Foo.exe").writeText("stub")
        val inFolder = LutrisImport.resolveInFolder(plan(listOf("drive_c", "bin", "Foo.exe")), root)
        assertEquals("Foo.exe", inFolder.executable)
    }

    @Test
    fun `an executable must be a regular exe file`() {
        val root = dir()
        File(root, "Foo.txt").writeText("stub")
        File(root, "Setup.exe").mkdirs()
        File(root, "Launcher.EXE").writeText("stub")
        assertEquals(
            listOf(ImportLine("Executable Foo.txt", "There is no Foo.txt in this game's folder")),
            LutrisImport.resolveInFolder(plan(listOf("Foo.txt")), root).notImported,
        )
        assertEquals(
            listOf(ImportLine("Executable Setup.exe", "There is no Setup.exe in this game's folder")),
            LutrisImport.resolveInFolder(plan(listOf("Setup.exe")), root).notImported,
        )
        assertEquals("Launcher.EXE", LutrisImport.resolveInFolder(plan(listOf("Launcher.EXE")), root).executable)
    }

    @Test
    fun `a symlink cannot lead the executable out of the game folder`() {
        val root = dir()
        val outside = dir()
        val target = File(outside, "Real.exe")
        target.writeText("stub")
        Files.createSymbolicLink(File(root, "Link.exe").toPath(), target.toPath())
        val inFolder = LutrisImport.resolveInFolder(plan(listOf("Link.exe")), root)
        assertNull(inFolder.executable)
        assertEquals(
            listOf(ImportLine("Executable Link.exe", "There is no Link.exe in this game's folder")),
            inFolder.notImported,
        )
    }

    @Test
    fun `a working folder resolves, a missing one is refused`() {
        val root = dir()
        File(root, "Foo/bin").mkdirs()
        val inFolder = LutrisImport.resolveInFolder(plan(null, listOf("drive_c", "Foo", "bin")), root)
        assertEquals("Foo/bin", inFolder.workingDir)
        assertTrue(inFolder.notImported.isEmpty())
        val missing = LutrisImport.resolveInFolder(plan(null, listOf("drive_c", "Bar")), root)
        assertNull(missing.workingDir)
        assertEquals(
            listOf(ImportLine("Working folder drive_c/Bar", "No folder like it is in this game's folder")),
            missing.notImported,
        )
    }

    @Test
    fun `a GAMEDIR working folder is the game folder itself`() {
        val inFolder = LutrisImport.resolveInFolder(plan(null, emptyList<String>()), dir())
        assertEquals("", inFolder.workingDir)
        assertTrue(inFolder.notImported.isEmpty())
    }

    @Test
    fun `a script without an executable and folder imports neither`() {
        val inFolder = LutrisImport.resolveInFolder(plan(null, null), dir())
        assertNull(inFolder.executable)
        assertNull(inFolder.workingDir)
        assertTrue(inFolder.notImported.isEmpty())
    }
}

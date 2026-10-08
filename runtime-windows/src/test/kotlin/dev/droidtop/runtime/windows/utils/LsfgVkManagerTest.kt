package dev.droidtop.runtime.windows.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What LSFG frame generation reads and writes that needs no device: finding
 * the person's Lossless.dll in a Steam install, the multiplier a stored choice
 * means, and the two files the layer is given.
 */
class LsfgVkManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(vararg segments: String): File =
        segments.fold(tmp.root) { parent, seg -> File(parent, seg) }.also { it.parentFile?.mkdirs(); it.createNewFile() }

    @Test
    fun theDllIsFoundWhateverItsLetterCase() {
        file("ls", "lossless.DLL")
        assertEquals("lossless.DLL", LsfgVkManager.findDll(File(tmp.root, "ls"))?.name)
    }

    @Test
    fun theDllIsFoundOneFolderDown() {
        file("ls", "bin", "Lossless.dll")
        assertEquals(File(tmp.root, "ls/bin"), LsfgVkManager.findDll(File(tmp.root, "ls"))?.parentFile)
    }

    @Test
    fun anInstallWithoutTheDllHasNone() {
        file("ls", "LosslessScaling.exe")
        assertNull(LsfgVkManager.findDll(File(tmp.root, "ls")))
        assertNull(LsfgVkManager.findDll(File(tmp.root, "not-there")))
    }

    @Test
    fun aMultiplierOutsideTwoToFourIsTheDefault() {
        assertEquals(3, LsfgVkManager.multiplier("3"))
        assertEquals(4, LsfgVkManager.multiplier("4"))
        assertEquals(LsfgVkManager.DEFAULT_MULTIPLIER, LsfgVkManager.multiplier("1"))
        assertEquals(LsfgVkManager.DEFAULT_MULTIPLIER, LsfgVkManager.multiplier("9"))
        assertEquals(LsfgVkManager.DEFAULT_MULTIPLIER, LsfgVkManager.multiplier("off"))
        assertEquals(LsfgVkManager.DEFAULT_MULTIPLIER, LsfgVkManager.multiplier(null))
    }

    @Test
    fun theConfigNamesTheDllAndTheMultiplier() {
        val toml = LsfgVkManager.configToml("/home/x/.local/share/lsfg-vk/Lossless.dll", 3)
        assertTrue(toml.contains("dll = \"/home/x/.local/share/lsfg-vk/Lossless.dll\""))
        assertTrue(toml.contains("multiplier = 3"))
        assertTrue(toml.contains("exe = \"droidtop-lsfg\""))
    }

    @Test
    fun aPathWithQuotesIsEscapedInTheConfig() {
        assertTrue(LsfgVkManager.configToml("C:\\a\"b", 2).contains("dll = \"C:\\\\a\\\"b\""))
    }

    @Test
    fun theManifestPointsUpFromTheLayerFolderToTheLibrary() {
        val json = LsfgVkManager.manifestJson()
        assertTrue(json.contains("\"library_path\": \"../../../lib/liblsfg-vk-layer.so\""))
    }
}

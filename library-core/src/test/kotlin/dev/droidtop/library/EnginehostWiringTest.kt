package dev.droidtop.library

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract semantics the enginehost wiring implements — the
 * series-coverage rule verbatim from the coordination responses
 * (2026-08-31: "an 8.2 bundle matches every 8.2.* version while never
 * matching 8.3"), and the new version detectors against the real file
 * shapes the same responses name as authoritative.
 */
class EnginehostWiringTest {

    private val temporaryDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryDirs.forEach { it.deleteRecursively() }
    }

    private fun dir(): File = Files.createTempDirectory("eh").toFile().also { temporaryDirs += it }

    // ---- supportedSeries rule -------------------------------------------

    @Test
    fun `a series covers every deeper version in its line`() {
        assertTrue(EnginehostCapabilities.seriesCovers("8.2", "8.2.1.24030407"))
        assertTrue(EnginehostCapabilities.seriesCovers("8.2", "8.2"))
        assertTrue(EnginehostCapabilities.seriesCovers("8", "8.3.2"))
    }

    @Test
    fun `a series never covers a sibling line`() {
        assertFalse(EnginehostCapabilities.seriesCovers("8.2", "8.3.2.24090902"))
    }

    @Test
    fun `series matching is by components, not characters`() {
        // "8.2" as a string prefix WOULD match "8.20.1" -- the rule is
        // component equality, and this is the case that catches a
        // startsWith shortcut.
        assertFalse(EnginehostCapabilities.seriesCovers("8.2", "8.20.1"))
    }

    @Test
    fun `a version shallower than the series is not covered`() {
        assertFalse(EnginehostCapabilities.seriesCovers("8.2.1", "8.2"))
    }

    // ---- Twine -----------------------------------------------------------

    @Test
    fun `twine version comes from tw-storydata creator-version`() {
        val root = dir()
        File(root, "Routes of Life.html").writeText(
            "<html><body><tw-storydata name=\"Routes\" creator=\"Twine\" creator-version=\"2.7.1\" format=\"SugarCube\">"
        )
        val detected = EngineVersionDetector.detect(GameEngine.TWINE, root)
        assertEquals("2.7.1", detected?.version)
    }

    @Test
    fun `an html without tw-storydata is not called twine-versioned`() {
        val root = dir()
        File(root, "readme.html").writeText("<html><body>hello</body></html>")
        assertNull(EngineVersionDetector.detect(GameEngine.TWINE, root))
    }

    // ---- SWF -------------------------------------------------------------

    @Test
    fun `swf version is header byte 4 across all three signatures`() {
        for ((signature, version) in listOf("FWS" to 6, "CWS" to 15, "ZWS" to 32)) {
            val root = dir()
            File(root, "game.swf").writeBytes(
                signature.toByteArray(Charsets.US_ASCII) + byteArrayOf(version.toByte(), 0, 0, 0, 0)
            )
            val detected = EngineVersionDetector.detect(GameEngine.FLASH_AIR, root)
            assertEquals("expected $signature v$version", version.toString(), detected?.version)
        }
    }

    @Test
    fun `a wrong signature is rejected rather than read`() {
        val root = dir()
        File(root, "game.swf").writeBytes("XXX".toByteArray() + byteArrayOf(9))
        assertNull(EngineVersionDetector.detect(GameEngine.FLASH_AIR, root))
    }

    // ---- RPG Maker MV/MZ constant ---------------------------------------

    @Test
    fun `the RPGMAKER_VERSION constant beats the banner comment`() {
        val root = dir()
        File(root, "js").mkdirs()
        // A repack whose banner disagrees with the code constant -- the
        // constant is the deployed runtime per enginehost's guidance.
        File(root, "js/rpg_core.js").writeText(
            "//=====\n// rpg_core.js v1.5.0\n//=====\nUtils.RPGMAKER_VERSION = '1.6.2';\n"
        )
        assertEquals("1.6.2", EngineVersionDetector.detect(GameEngine.RPG_MAKER_MV, root)?.version)
    }
}

package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fixtures here are verbatim from four real games in the user's own
 * library, read over adb -- not invented shapes. See
 * [EngineVersionDetector]'s own doc comment for which game each came from.
 */
class EngineVersionDetectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(content: String, vararg segments: String) {
        val file = segments.fold(tmp.root) { dir, seg -> File(dir, seg) }
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun mkdirs(vararg segments: String) {
        segments.fold(tmp.root) { dir, seg -> File(dir, seg) }.mkdirs()
    }

    @Test
    fun `reads the exact version from renpy vc_version-dot-py`() {
        // Verbatim from Eternum-0.9.5-pc/renpy/vc_version.py.
        write(
            """
            branch = 'fix'
            nightly = False
            official = True
            version = '8.3.2.24090902'
            version_name = 'Second Star to the Right'
            """.trimIndent(),
            "renpy", "vc_version.py",
        )

        val detected = EngineVersionDetector.detect(GameEngine.RENPY, tmp.root)
        // Verbatim, all four components -- enginehost's own config
        // creator emits the same full form (observed on-device against
        // a real Ren'Py game: 8.2.1.24030407).
        assertEquals("8.3.2.24090902", detected?.version)
        assertEquals("renpy/vc_version.py", detected?.source)
    }

    @Test
    fun `falls back to the RenPy banner in log-dot-txt when vc_version is stripped`() {
        // Verbatim shape from Eternum-0.9.5-pc/log.txt's first three lines.
        write(
            """
            2026-05-01 23:54:42 UTC
            Linux-6.17.7-ba29.fc43.x86_64-x86_64-with-glibc2.42
            Ren'Py 8.3.2.24090902
            """.trimIndent(),
            "log.txt",
        )

        val detected = EngineVersionDetector.detect(GameEngine.RENPY, tmp.root)
        assertEquals("8.3.2.24090902", detected?.version)
        assertEquals("log.txt", detected?.source)
    }

    @Test
    fun `reports only the family for a repack with every version file stripped`() {
        // 30YearOldVirgin-0.37.dv-pc: renpy/ reduced to audio+uguu, and
        // lib/python3.12 is the only surviving version signal.
        mkdirs("lib", "python3.12")
        mkdirs("renpy", "audio")

        val detected = EngineVersionDetector.detect(GameEngine.RENPY, tmp.root)
        // Never fabricates a precise version it cannot actually read.
        assertNull(detected?.version)
        assertEquals("8", detected?.family)
    }

    @Test
    fun `recognizes a python2 RenPy 7 layout as the 7 family`() {
        // AnotherChance-v1.51-pc: lib/python2.7 plus bare linux-x86_64,
        // with no py3- prefixed platform directory.
        mkdirs("lib", "python2.7")
        mkdirs("lib", "linux-x86_64")

        assertEquals("7", EngineVersionDetector.detect(GameEngine.RENPY, tmp.root)?.family)
    }

    @Test
    fun `an exact version still reports the family alongside it`() {
        write("version = '8.3.2.24090902'\n", "renpy", "vc_version.py")
        mkdirs("lib", "py3-linux-x86_64")

        val detected = EngineVersionDetector.detect(GameEngine.RENPY, tmp.root)
        assertEquals("8.3.2.24090902", detected?.version)
        assertEquals("8", detected?.family)
    }

    @Test
    fun `reads the MV engine version from the core script header`() {
        // Verbatim from "How to Raise a Happy NEET 1.0.5" and
        // "luna_space_prison", both real MV exports using the www/ wrapper.
        write(
            """
            //=============================================================================
            // rpg_core.js v1.6.2
            //=============================================================================
            """.trimIndent(),
            "www", "js", "rpg_core.js",
        )

        val detected = EngineVersionDetector.detect(GameEngine.RPG_MAKER_MV, tmp.root)
        assertEquals("1.6.2", detected?.version)
        assertEquals("rpg_core.js", detected?.source)
    }

    @Test
    fun `reads the MZ engine version, unwrapped`() {
        // "Magical Princess Lily" ships MZ without the www/ wrapper.
        write("//=====\n// rmmz_core.js v1.8.0\n//=====\n", "js", "rmmz_core.js")

        assertEquals("1.8.0", EngineVersionDetector.detect(GameEngine.RPG_MAKER_MZ, tmp.root)?.version)
    }

    @Test
    fun `an MV export with no version header detects as null`() {
        write("// some patched core with the header stripped\n", "www", "js", "rpg_core.js")
        assertNull(EngineVersionDetector.detect(GameEngine.RPG_MAKER_MV, tmp.root))
    }

    @Test
    fun `reads the RGSS version from Game-dot-ini`() {
        // Verbatim from MGQ Paradox 3.06.
        write(
            """
            [Game]
            Library=System\RGSS301.dll
            Scripts=Data\Scripts.rvdata2
            Title=Monster Girl Quest! Paradox RPG
            """.trimIndent(),
            "Game.ini",
        )

        val detected = EngineVersionDetector.detect(GameEngine.RPG_MAKER_VX_ACE, tmp.root)
        assertEquals("3.01", detected?.version)
    }

    @Test
    fun `engines with no verified signature yet detect as null rather than guessing`() {
        // Kirikiri has no verified version signature yet -- deliberately
        // null rather than a plausible guess (see the class doc comment).
        write("anything", "data.xp3")
        assertNull(EngineVersionDetector.detect(GameEngine.KIRIKIRI, tmp.root))
    }
}

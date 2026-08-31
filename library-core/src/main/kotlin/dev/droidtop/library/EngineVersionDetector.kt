package dev.droidtop.library

import java.io.File

/**
 * Detects the real runtime version a detected engine game targets — the
 * missing half of [GameEngineDetector], which only ever determined WHICH
 * engine (see its own doc comment). enginehost's launch contract requires
 * a dotted-numeric `engineVersion` for the game's true runtime target, so
 * without this droidtop could almost never offer
 * [GameLaunchStrategy.ENGINEHOST] at all: [resolveEngineVersion] returned
 * null for every folder without its own hand-written `enginehost.json`,
 * the resolver dropped ENGINEHOST from the available list, and real
 * Ren'Py games fell through to [GameLaunchStrategy.WINE_PREFIX] — running
 * a game through Wine that has a real native runtime available. That
 * fallback was the bug, not the design.
 *
 * Every signature here was read off REAL games in the user's own library
 * over adb, never guessed — and where a real game genuinely doesn't carry
 * its version, this returns null rather than inventing a plausible one
 * (a fabricated "8.0.0" handed to a host that will try to honor it
 * exactly is worse than admitting the version is unknown).
 */
object EngineVersionDetector {

    /**
     * A detected runtime version. [version] is the dotted-numeric engine
     * version when one was genuinely readable; [family] is the coarse
     * major-version line ("8", "7") derived from the interpreter/platform
     * library layout, which survives the aggressive repacking real
     * distributions get even when every version file has been stripped.
     * [source] names the real evidence, for UI and log honesty.
     */
    data class DetectedVersion(
        val version: String?,
        val family: String?,
        val source: String,
    )

    fun detect(engine: GameEngine, gameRoot: File): DetectedVersion? = when (engine) {
        GameEngine.RENPY -> detectRenPy(gameRoot)
        GameEngine.RPG_MAKER_VX_ACE -> detectRgss(gameRoot)
        // Deliberately absent: every other engine's version signature is
        // unverified against a real game so far. Adding a guessed one
        // would produce exactly the confidently-wrong engineVersion this
        // class exists to avoid -- they resolve through the existing
        // per-folder override / enginehost.json paths until a real sample
        // is available to verify a signature against.
        else -> null
    }

    // ---- RPG Maker (RGSS family: XP, VX, VX Ace) -------------------------
    //
    // Verified against MGQ Paradox 3.06 on the user's own device, which is
    // the exact game enginehost's own notes call out for its RGSS301
    // metadata. Its Game.ini reads:
    //
    //     [Game]
    //     Library=System\RGSS301.dll
    //     Scripts=Data\Scripts.rvdata2
    //
    // Game.ini is RPG Maker's own runtime configuration file and the
    // Library line names the exact RGSS build the game was made for, so
    // this is the engine declaring its own version rather than droidtop
    // inferring one. The DLL name encodes it as RGSS<major><minor,2
    // digits>[locale letter]: RGSS301 -> 3.01 (which is precisely the
    // engineVersion enginehost's own contract uses in its vxace example),
    // RGSS202E -> 2.02, RGSS104E -> 1.04.

    private val RGSS_LIBRARY_LINE = Regex("""Library\s*=\s*.*?RGSS(\d)(\d{2})""", RegexOption.IGNORE_CASE)

    /** RGSS major version -> enginehost's own RPG Maker generation context. */
    fun rgssGenerationContext(majorRgss: Int): String? = when (majorRgss) {
        1 -> "xp"
        2 -> "vx"
        3 -> "vxace"
        else -> null
    }

    private fun detectRgss(gameRoot: File): DetectedVersion? {
        val text = readHead(File(gameRoot, "Game.ini")) ?: return null
        val match = RGSS_LIBRARY_LINE.find(text) ?: return null
        val major = match.groupValues[1]
        val minor = match.groupValues[2]
        return DetectedVersion(
            version = "$major.$minor",
            family = major,
            source = "Game.ini (Library=RGSS$major$minor)",
        )
    }

    // ---- Ren'Py ----------------------------------------------------------
    //
    // Verified against four real games on the user's own device:
    //   Eternum-0.9.5-pc         renpy/vc_version.py + log.txt banner, lib/py3-*, lib/python3.9
    //   AnotherChance-v1.51-pc   renpy/vc_version.py, lib/python2.7 + bare lib/linux-x86_64
    //   BeingADik/...-scrappy    lib/python2.7 (markers one folder deeper, see GameEngineDetector.scan)
    //   30YearOldVirgin-0.37...  renpy/ stripped to audio+uguu, ONLY lib/python3.12 survives

    private val VC_VERSION_LINE = Regex("""^\s*version\s*=\s*['"]([0-9][0-9.]*)['"]""", RegexOption.MULTILINE)
    private val LOG_BANNER = Regex("""Ren'Py\s+([0-9][0-9.]*)""")

    private fun detectRenPy(gameRoot: File): DetectedVersion? {
        val family = renPyFamily(gameRoot)

        // 1. renpy/vc_version.py -- Ren'Py's own canonical version file,
        //    e.g. `version = '8.3.2.24090902'`. Reported VERBATIM, all four
        //    components. An earlier draft trimmed to three on the strength
        //    of the contract's "e.g. 8.3.2" wording, which was wrong:
        //    enginehost's own config creator, observed running on this
        //    device against a real Ren'Py game, fills engineVersion with
        //    the full `8.2.1.24030407`. Since resolution matches versions
        //    exactly (and only trailing ZERO components compare equal, so
        //    a build serial is not droppable), sending a trimmed version
        //    would silently fail to resolve a bundle the full one matches.
        readHead(File(gameRoot, "renpy/vc_version.py"))?.let { text ->
            VC_VERSION_LINE.find(text)?.groupValues?.get(1)?.let { raw ->
                return DetectedVersion(raw, family, "renpy/vc_version.py")
            }
        }

        // 2. log.txt -- Ren'Py writes its own full version banner there on
        //    every run ("Ren'Py 8.3.2.24090902"), so a game that has been
        //    played once still identifies itself even when vc_version.py
        //    was stripped out of the distribution.
        readHead(File(gameRoot, "log.txt"))?.let { text ->
            LOG_BANNER.find(text)?.groupValues?.get(1)?.let { raw ->
                return DetectedVersion(raw, family, "log.txt")
            }
        }

        // 3. Nothing exact survived -- report only the real family signal
        //    (or nothing), never a fabricated precise version.
        return family?.let { DetectedVersion(version = null, family = it, source = "lib/ layout") }
    }

    /**
     * Ren'Py's major line from the interpreter/platform libraries it
     * ships: 8.x is a Python 3 build (`lib/py3-<platform>`, `lib/python3.N`),
     * 7.x is Python 2 (`lib/python2.7`, and bare `lib/linux-x86_64` with no
     * `py3-` prefix). This is the one signal that survived the most
     * heavily repacked real game checked (whose entire renpy/ tree was
     * gone but lib/python3.12 remained).
     */
    private fun renPyFamily(gameRoot: File): String? {
        val names = File(gameRoot, "lib").list()?.map { it.lowercase() } ?: return null
        return when {
            names.any { it.startsWith("py3-") || it.startsWith("python3") } -> "8"
            names.any { it.startsWith("python2") } -> "7"
            else -> null
        }
    }

    /**
     * First 8KB only: every file read here identifies itself in its first
     * handful of lines, and a real game's log.txt grows without bound.
     */
    private fun readHead(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { String(it.readNBytes(8 * 1024), Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            null
        }
    }
}

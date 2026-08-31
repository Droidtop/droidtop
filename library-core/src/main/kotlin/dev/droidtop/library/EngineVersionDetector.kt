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
        // Deliberately absent: every other engine's version signature is
        // unverified against a real game so far. Adding a guessed one
        // would produce exactly the confidently-wrong engineVersion this
        // class exists to avoid -- they resolve through the existing
        // per-folder override / enginehost.json paths until a real sample
        // is available to verify a signature against.
        else -> null
    }

    // ---- Ren'Py ----------------------------------------------------------
    //
    // Verified against four real games on the user's own device:
    //   Eternum-0.9.5-pc         renpy/vc_version.py + log.txt banner, lib/py3-*, lib/python3.9
    //   AnotherChance-v1.51-pc   renpy/vc_version.py, lib/python2.7 + bare lib/linux-x86_64
    //   BeingADik/...-scrappy    lib/python2.7 (markers one folder deeper, see GameEngineDetector.scan)
    //   30YearOldVirgin-0.37...  renpy/ stripped to audio+uguu, ONLY lib/python3.12 survives

    private val VC_VERSION_LINE = Regex("""^\s*version\s*=\s*['"]([0-9][0-9.]*)['"]""", RegexOption.MULTILINE)
    private val LOG_BANNER = Regex("""Ren'Py\s+([0-9]+\.[0-9]+(?:\.[0-9]+)?)""")

    private fun detectRenPy(gameRoot: File): DetectedVersion? {
        val family = renPyFamily(gameRoot)

        // 1. renpy/vc_version.py -- Ren'Py's own canonical version file,
        //    e.g. `version = '8.3.2.24090902'`. The trailing component is
        //    a build serial, not part of the engine version line, so only
        //    the first three components are kept.
        readHead(File(gameRoot, "renpy/vc_version.py"))?.let { text ->
            VC_VERSION_LINE.find(text)?.groupValues?.get(1)?.let { raw ->
                return DetectedVersion(trimToEngineVersion(raw), family, "renpy/vc_version.py")
            }
        }

        // 2. log.txt -- Ren'Py writes its own full version banner there on
        //    every run ("Ren'Py 8.3.2.24090902"), so a game that has been
        //    played once still identifies itself even when vc_version.py
        //    was stripped out of the distribution.
        readHead(File(gameRoot, "log.txt"))?.let { text ->
            LOG_BANNER.find(text)?.groupValues?.get(1)?.let { raw ->
                return DetectedVersion(trimToEngineVersion(raw), family, "log.txt")
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

    /** `8.3.2.24090902` -> `8.3.2`; `8.3` stays `8.3`. */
    private fun trimToEngineVersion(raw: String): String =
        raw.split('.').filter { it.isNotEmpty() }.take(3).joinToString(".")

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

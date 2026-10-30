package dev.droidtop.runtime.power

import android.content.Context
import dev.droidtop.runtime.systemstatus.GameMode
import dev.droidtop.runtime.systemstatus.GameModeControl
import dev.droidtop.runtime.tasks.PrivilegedShell

/**
 * The power policy's standard layer (docs/SPEC.md "Power policy", Droidtop/tracker#841 slice 3a): Android's Game Mode
 * for the package in front, the one layer every device with the elevated shell has. Saver is Android's battery mode,
 * Standard its standard, Performance and Max its performance.
 *
 * Android keeps a package's mode after droidtop exits, so every change is written to [PowerJournal] first, with the
 * mode found before it, and put back when the game closes ([restore]) or, when droidtop stopped first, at its next
 * start ([restoreAll]). Blocking: IO only.
 */
object StandardPowerLayer {
    fun modeFor(level: PowerLevel): GameMode = when (level) {
        PowerLevel.SAVER -> GameMode.BATTERY
        PowerLevel.STANDARD -> GameMode.STANDARD
        PowerLevel.PERFORMANCE, PowerLevel.MAX -> GameMode.PERFORMANCE
    }

    /** What applying a level did. */
    enum class Outcome { SET, ALREADY, IGNORED, NO_SHELL, FAILED }

    /** Asks for [level] for [packageName], journaling the mode it found first. */
    fun apply(context: Context, shell: PrivilegedShell, level: PowerLevel, packageName: String): Outcome {
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return Outcome.NO_SHELL
        val wanted = modeFor(level)
        val before = read(shell, packageName)
        if (before == wanted) return Outcome.ALREADY
        // Written before the change, so a crash between the two still leaves a way back.
        PowerJournal.remember(context, packageName, before ?: GameMode.STANDARD)
        if (!GameModeControl.set(shell, wanted, packageName)) return Outcome.FAILED
        val after = read(shell, packageName) ?: return Outcome.SET
        return if (after == wanted) Outcome.SET else Outcome.IGNORED
    }

    /** Puts back the mode found before droidtop's first change to [packageName]; true when nothing is left to undo. */
    fun restore(context: Context, shell: PrivilegedShell, packageName: String): Boolean {
        val before = PowerJournal.entries(context)[packageName] ?: return true
        if (!GameModeControl.set(shell, before, packageName)) return false
        PowerJournal.forget(context, packageName)
        return true
    }

    /** Puts back every journaled package: droidtop's start, Auto turned off, or the shell back after it died. */
    fun restoreAll(context: Context, shell: PrivilegedShell): Int =
        PowerJournal.entries(context).keys.count { restore(context, shell, it) }

    /** The mode Android has for [packageName], from `dumpsys game`; null when the dump says nothing this can read. */
    fun read(shell: PrivilegedShell, packageName: String): GameMode? {
        val dump = runCatching { shell.exec(listOf("dumpsys", "game")) }.getOrNull()?.takeIf { it.exit == 0 }?.stdout
        return GameModeReadBack.parse(dump.orEmpty(), packageName)
    }
}

/**
 * The game mode of one package in `dumpsys game`'s text. The RP5's Android 13 has no `cmd game list-modes`
 * (console, 2026-10-30), so the dump is the read-back. Its wording differs between releases, so the parse looks for
 * the package, then a "mode" with a mode's name or number on that line or the next few. Pure.
 */
object GameModeReadBack {
    private val MODE = Regex("""(?i)mode\W{0,3}(standard|performance|battery|custom|unsupported|[0-4])\b""")
    private const val LOOK_AHEAD = 4

    fun parse(dump: String, packageName: String): GameMode? {
        val lines = dump.lines()
        val at = lines.indexOfFirst { line -> Regex("""(^|[^\w.])${Regex.escape(packageName)}($|[^\w.])""").containsMatchIn(line) }
        if (at < 0) return null
        for (line in lines.subList(at, minOf(lines.size, at + 1 + LOOK_AHEAD))) {
            val word = MODE.find(line)?.groupValues?.get(1)?.lowercase() ?: continue
            return when (word) {
                "standard", "1" -> GameMode.STANDARD
                "performance", "2" -> GameMode.PERFORMANCE
                "battery", "3" -> GameMode.BATTERY
                else -> null
            }
        }
        return null
    }
}

/**
 * What droidtop changed and what was there before, one package to a line, kept in its own preferences so a crash or
 * a stop leaves the way back (docs/SPEC.md "Power policy", the journal). Disk: IO only.
 */
object PowerJournal {
    private const val PREFS = "power_journal"
    private const val KEY = "game_modes"

    fun entries(context: Context): Map<String, GameMode> =
        decode(prefs(context).getString(KEY, null).orEmpty())

    /** Records [before] for [packageName] unless a change is already journaled: the first "before" is the one to keep. */
    @android.annotation.SuppressLint("ApplySharedPref") // on disk before Android's mode changes
    fun remember(context: Context, packageName: String, before: GameMode) {
        val now = entries(context)
        if (packageName in now) return
        prefs(context).edit().putString(KEY, encode(now + (packageName to before))).commit()
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    fun forget(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY, encode(entries(context) - packageName)).commit()
    }

    internal fun encode(entries: Map<String, GameMode>): String =
        entries.entries.joinToString("") { (pkg, mode) -> "$pkg=${mode.key}\n" }

    internal fun decode(text: String): Map<String, GameMode> =
        text.lineSequence().mapNotNull { line ->
            val (pkg, mode) = line.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            pkg.trim().takeIf { it.isNotEmpty() }?.let { it to GameMode.fromKey(mode.trim()) }
        }.toMap()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

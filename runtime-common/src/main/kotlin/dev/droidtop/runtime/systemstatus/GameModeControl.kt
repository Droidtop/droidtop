package dev.droidtop.runtime.systemstatus

import dev.droidtop.runtime.tasks.PrivilegedShell

/**
 * Android's per-game performance profile (GameManager, Android 12 and later), set through the `priv.shell`
 * provider's `cmd game`: the one performance control the handheld allows without root (docs/SPEC.md, "Performance
 * overlay"). It only changes how a package Android counts as a game is run, and Android keeps the mode, so it is
 * set once and holds across launches.
 */
enum class GameMode(val key: String, val label: String) {
    STANDARD("standard", "Standard"),
    PERFORMANCE("performance", "Performance"),
    BATTERY("battery", "Battery saver");

    fun next(): GameMode = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromKey(key: String?): GameMode = values().firstOrNull { it.key == key } ?: STANDARD
    }
}

object GameModeControl {
    /** Android 14 spells the command `cmd game set --mode <mode> <package>`; 12 and 13 `cmd game mode <mode> <package>`. */
    fun commands(mode: GameMode, packageName: String): List<List<String>> = listOf(
        listOf("cmd", "game", "set", "--mode", mode.key, "--user", "0", packageName),
        listOf("cmd", "game", "mode", "--user", "0", mode.key, packageName),
    )

    /** Sets [mode] for [packageName]; true when one spelling of the command was accepted. Blocks on the provider. */
    fun set(shell: PrivilegedShell, mode: GameMode, packageName: String): Boolean =
        commands(mode, packageName).any { argv ->
            val out = runCatching { shell.exec(argv) }.getOrNull()
            out != null && out.exit == 0 && !refused(out.stdout + out.stderr)
        }

    /** `cmd` can exit 0 and still print that the service or the mode is unknown. */
    fun refused(output: String): Boolean {
        val text = output.lowercase()
        return "unknown command" in text || "exception" in text || "error" in text || "usage" in text
    }
}

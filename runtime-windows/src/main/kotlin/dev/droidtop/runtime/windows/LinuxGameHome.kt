package dev.droidtop.runtime.windows

import android.content.Context
import java.io.File
import java.util.zip.CRC32

/**
 * The home a native Linux game keeps its saves and settings in when the person asked for one of its own
 * (docs/SPEC.md 7c, "Prefix tools for Linux games"): a folder in droidtop's app storage, named for the
 * game, that the container sees at the app-storage mount. The game gets `HOME` and the XDG folders
 * pointed into it; nothing is copied there, and the container's own home is never touched.
 */
internal object LinuxGameHome {

    /** The folder, on the host, for [entryId]'s home. Not created. */
    fun dir(context: Context, entryId: String): File =
        File(File(context.filesDir, "linux-games"), safeName(entryId) + "/home")

    /** [entryId] as a folder name: letters, digits, dash and underscore, plus a checksum so two ids never share one. */
    fun safeName(entryId: String): String {
        val plain = entryId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(40)
        val crc = CRC32().apply { update(entryId.toByteArray()) }.value
        return "$plain-${crc.toString(16)}"
    }

    /** The environment that points a game at [home], a path inside the container. */
    fun environment(home: String): Map<String, String> = mapOf(
        "HOME" to home,
        "XDG_CONFIG_HOME" to "$home/.config",
        "XDG_DATA_HOME" to "$home/.local/share",
        "XDG_CACHE_HOME" to "$home/.cache",
        "XDG_STATE_HOME" to "$home/.local/state",
    )

    /**
     * A shell script, run in the container, that ends every process whose command line mentions the path
     * in `DT_STOP_PATTERN` (the game's folder). The path travels in the environment, not the command line,
     * so neither this script nor the wrapper that starts it matches itself; the script skips its own pid.
     */
    const val STOP_SCRIPT =
        "set -u; self=\$\$; n=0; " +
            "for d in /proc/[0-9]*; do pid=\${d#/proc/}; [ \"\$pid\" = \"\$self\" ] && continue; " +
            "if tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null | grep -q -F -- \"\$DT_STOP_PATTERN\"; then kill \"\$pid\" 2>/dev/null && n=\$((n+1)); fi; done; echo \$n"

    /** The script that empties the home in `DT_HOME`: its contents only, and only when the variable is set. */
    const val RESET_SCRIPT = "cd \"\${DT_HOME:?}\" && find . -mindepth 1 -delete"
}

package dev.droidtop.library.controller

import android.util.Log
import dev.droidtop.runtime.tasks.TaskManager

/**
 * The system properties droidtop reads to follow a layout toggle that can
 * change while the app is in front (docs/SPEC.md 7b, "Console and controller
 * detection"). Never `su`: a plain read, and when SELinux hides the property
 * from an untrusted app, the privileged helper (Shizuku or Sui) if there is
 * one, else the answer is "unknown".
 */
object LayoutSignals {
    private const val TAG = "droidtop.ControllerLayout"

    /** Property names that look like a button-layout toggle. `ro.` ones never change while the device runs. */
    private val WATCHED = Regex("(?i)(gamepad|joystick|abxy|swap|keymap)")

    /**
     * One property by `android.os.SystemProperties.get`, which is an
     * in-memory read of the property area: cheap enough for the main thread
     * and the screen-change check. Null when the call is not available or
     * the property reads as empty, which is also what a property hidden by
     * SELinux looks like, so empty is never taken as a value.
     */
    fun readInProcess(name: String): String? = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.trim()?.ifEmpty { null }
    } catch (t: Throwable) {
        null
    }

    /** One property through the privileged helper when one is available; blocks on it, so never the main thread. */
    fun readPrivileged(name: String): String? {
        val shell = TaskManager.shell
        if (!shell.capabilities().shell) return null
        val out = shell.exec(listOf("getprop", name)) ?: return null
        return out.stdout.trim().ifEmpty { null }.takeIf { out.exit == 0 }
    }

    /**
     * Every readable property whose name looks like a layout toggle, by
     * running `getprop` as the app itself (it lists what the app is
     * allowed to see). A process spawn: never the main thread.
     */
    fun snapshot(): Map<String, String> = try {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor()
        parseGetprop(lines)
    } catch (t: Throwable) {
        Log.d(TAG, "getprop was not available", t)
        emptyMap()
    }

    /** `[name]: [value]` lines, kept when the name is watched. */
    fun parseGetprop(lines: List<String>): Map<String, String> {
        val out = sortedMapOf<String, String>()
        for (line in lines) {
            val match = GETPROP_LINE.matchEntire(line.trim()) ?: continue
            val name = match.groupValues[1]
            if (!name.startsWith("ro.") && WATCHED.containsMatchIn(name)) out[name] = match.groupValues[2]
        }
        return out
    }

    /** A short stable value for a snapshot: equal exactly when the watched properties are. */
    fun signature(snapshot: Map<String, String>): String =
        Integer.toHexString(snapshot.entries.sortedBy { it.key }.joinToString("\n") { it.key + "=" + it.value }.hashCode())

    /** What changed between two snapshots, one readable line each, for the log. */
    fun diff(before: Map<String, String>, after: Map<String, String>): List<String> =
        (before.keys + after.keys).sorted().mapNotNull { key ->
            val a = before[key]
            val b = after[key]
            if (a == b) null else "$key: ${a ?: "(absent)"} -> ${b ?: "(absent)"}"
        }

    private val GETPROP_LINE = Regex("""\[(.+?)]: \[(.*)]""")
}

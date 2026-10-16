package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * The files a Flutter plugin opened through the broker (`hostOpen`, docs/plugin-api.md 5.3 "What a contained plugin's
 * own folder holds", Droidtop/tracker#375): `PluginContext.openFile` hands over a descriptor, and Dart's `dart:io` opens
 * paths only, so each one gets a path. At most [MAX] at a time; `hostClose` lets one go. Pure, so the slots and the
 * reply are tested without a device; [FlutterDroidtopPlugin] gives the descriptor its path.
 */
internal class FlutterOpenFiles {
    private val taken = BooleanArray(MAX)

    /** A free slot, or null when [MAX] files are open. */
    @Synchronized
    fun take(): Int? = taken.indexOfFirst { !it }.takeIf { it >= 0 }?.also { taken[it] = true }

    /** Frees the slot [path] names; false when it names none that is taken. */
    @Synchronized
    fun free(path: String): Boolean {
        val slot = slotOf(path) ?: return false
        if (!taken[slot]) return false
        taken[slot] = false
        return true
    }

    companion object {
        const val MAX = 64

        /** The path of [slot] in a process loaded from descriptors: a local file of the sandbox library. */
        fun pathOf(slot: Int): String = SandboxFiles.OPENED + slot

        fun slotOf(path: String): Int? =
            path.takeIf { it.startsWith(SandboxFiles.OPENED) }?.removePrefix(SandboxFiles.OPENED)?.toIntOrNull()?.takeIf { it in 0 until MAX }

        /** The broker's [reply] (JSON text) with the file's [path] added to its data. */
        fun withPath(reply: String, path: String): String {
            val json = JSONObject(reply)
            val data = json.optJSONObject("data") ?: JSONObject().also { json.put("data", it) }
            data.put("path", path)
            return json.toString()
        }
    }
}

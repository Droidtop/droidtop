package dev.droidtop.pluginhost

/**
 * One place that maps raw plugin-load failure reasons (kept full in
 * logcat, tag `droidtop.plugin`) to the plain sentence shown to a user.
 * Each kind from Droidtop/tracker#167: missing runtime, didn't signal
 * ready (plugin needs an update from its developer), timed out, crashed,
 * and incompatible API version.
 */
object PluginLoadErrorMessage {
    /** Plain sentence for the user; the full technical [raw] stays in logcat. */
    fun userMessage(raw: String): String = when {
        raw.contains("runtime is not installed", ignoreCase = true) ->
            "The plugin needs a runtime that isn't installed."
        raw.contains("runtimeVersion", ignoreCase = true) &&
            raw.contains("does not match", ignoreCase = true) ->
            "The plugin's version isn't compatible with the installed runtime."
        raw.contains("_channel.invokeMethod('ready')", ignoreCase = true) ||
            raw.contains("never signaled ready", ignoreCase = true) ->
            "The plugin didn't signal ready; it needs an update from its developer."
        raw.contains("timed out", ignoreCase = true) ->
            "The plugin timed out."
        raw.contains("load failed:", ignoreCase = true) ||
            raw.contains("crashed", ignoreCase = true) ||
            raw.contains("load failed across the binder", ignoreCase = true) ->
            "The plugin crashed while loading."
        else -> "The plugin failed to load."
    }
}

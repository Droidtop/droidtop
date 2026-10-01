package dev.droidtop.pluginhost

import android.content.Context

/**
 * A runtime a plugin cannot run without and the device does not have yet
 * (docs/SPEC.md 12a, "A runtime the plugin needs"). [kind] is the plugin
 * kind that needs it, [runtime] its name as a person reads it, [sizeLabel]
 * the download size.
 */
data class RuntimeNeed(val kind: PluginKind, val runtime: String, val sizeLabel: String) {
    /** The sentence every surface uses for this need, so a plugin page, a search result and a job all say the same thing. */
    val message: String get() = "needs the $runtime runtime ($sizeLabel), which is not installed yet"

    /** What the install action is called on every surface that offers it. */
    val actionLabel: String get() = "Download the $runtime runtime ($sizeLabel)"
}

/**
 * The one place that knows which plugin kinds need a downloaded runtime,
 * whether it is there, and how to get it. The plugin's own page, the
 * Accounts and sources counts, a failed search row and the call path
 * ([PluginCrashPolicy]) all ask here, so none of them says "Running" for a
 * plugin that cannot run, and the download is one mechanism with one set
 * of words.
 */
object PluginRuntimeNeeds {
    /** The runtime [manifest]'s kind needs that is not installed, or null when it needs none or has it. Reads a marker file: call off the main thread. */
    fun missing(context: Context, manifest: PluginManifest): RuntimeNeed? = when (manifest.kind) {
        PluginKind.FLUTTER_EMBED ->
            if (FlutterRuntimeManager.isInstalled(context)) null else RuntimeNeed(PluginKind.FLUTTER_EMBED, "Flutter", "about 40 MB")
        PluginKind.PYTHON ->
            if (PythonRuntimeManager.isInstalled(context)) null else RuntimeNeed(PluginKind.PYTHON, "Python", "about 22 MB")
        else -> null
    }

    /** Downloads, verifies and installs [need]'s runtime as a job, reporting progress as plain text. Returns null on success, otherwise the reason. Off the main thread; never throws. */
    suspend fun install(context: Context, need: RuntimeNeed, onStatus: (String) -> Unit): String? = when (need.kind) {
        PluginKind.FLUTTER_EMBED -> FlutterRuntimeManager.ensureInstalled(context, onStatus)
        PluginKind.PYTHON -> PythonRuntimeManager.ensureInstalled(context, onStatus)
        else -> "this kind of plugin needs no runtime"
    }
}

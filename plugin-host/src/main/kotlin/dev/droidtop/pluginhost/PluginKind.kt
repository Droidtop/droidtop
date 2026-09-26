package dev.droidtop.pluginhost

/**
 * The form a plugin bundle takes (docs/SPEC.md 12a). A plugin can be
 * "several forms" by the owner's own direction; this is the extensible
 * point -- add a case and a matching [PluginRunner] rather than
 * special-casing a new format through the existing ones.
 */
enum class PluginKind(val id: String) {
    /**
     * Real Android/Kotlin code: a dex payload droidtop loads with a
     * private-process [PluginRunner] (NativePluginRunner /
     * PluginRuntimeService), optionally carrying native `.so` libraries.
     * Native code ships arm64-v8a AND x86_64 (the standing bundle rule),
     * enforced by [PluginBundleInstaller].
     */
    NATIVE_BUNDLE("native_bundle"),

    /**
     * A single-file Python plugin (`plugin.py`), run by [PythonDroidtopPlugin]
     * inside a process-wide CPython interpreter that
     * [PythonBridge]/`plugin-host/native` embeds via `dlopen` +
     * the stable C API (docs/SPEC.md 12a, redecided 2026-09-26 after
     * Chaquopy was found to only compile CPython INTO whichever app
     * applies its Gradle plugin at that app's own build time -- see git
     * history on this file for that earlier, now-superseded text). The
     * interpreter itself comes from [PythonRuntimeManager], which
     * downloads the OFFICIAL per-ABI CPython Android build
     * (python.org/downloads/android/, PEP 738) on first use of a
     * python-kind plugin, verifies its SHA-256 against a pinned list
     * (`plugin-host/src/main/assets/python-runtimes.json`), and stores it
     * under `filesDir` -- never bundled in the base APK, exactly as the
     * decision requires, because this time the artifact genuinely is a
     * separate, independently-downloadable thing rather than a
     * build-time compilation step.
     */
    PYTHON("python"),

    /**
     * An embedded Flutter/Dart engine, hosted the same crash-contained
     * way a native bundle is (its own :pluginhost-process runner, once
     * built) rather than as a separate app. Documented as an open
     * extension point, not built: one real plugin candidate is an
     * existing Flutter app the owner wants to turn into a plugin rather
     * than rewrite (2026-09-25), and the [PluginKind]/[PluginRunner]
     * split exists precisely so that becomes "add a case and a runner"
     * later, not a redesign. A manifest declaring this kind validates
     * like any other and is refused activation with a clear reason,
     * same as [PYTHON].
     */
    FLUTTER_EMBED("flutter_embed"),
    ;

    companion object {
        fun fromId(id: String): PluginKind? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

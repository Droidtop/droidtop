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
     * A Python script or module. Documented here as the extension point
     * it is, not built: droidtop vendors no Python runtime today (grep
     * confirms it, see PluginRunner.kt's doc comment), and bundling one
     * (e.g. Chaquopy or python-for-android) is real, scoped follow-up
     * work, not a stub worth shipping now. A manifest that declares this
     * kind is accepted and validated like any other -- schema, hashes,
     * signature, ABI where it applies -- but [PluginStore] refuses to
     * activate it and says why, rather than pretending a runner exists.
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

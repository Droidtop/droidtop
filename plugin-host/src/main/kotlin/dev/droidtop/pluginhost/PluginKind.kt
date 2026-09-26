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
     * it is, not built. Blocked on a real conflict, not just missing
     * effort (docs/SPEC.md 12a, "python -- blocked on a real packaging
     * conflict"): Chaquopy, the runtime named for this kind, is a Gradle
     * plugin that compiles CPython into whichever app applies it at that
     * app's own build time -- there is no supported way to produce a
     * separate, later-downloadable Chaquopy runtime artifact, which is
     * what droidtop's own "never bundled in the base APK" requirement
     * needs. python-for-android has the same "build it in" shape and is
     * additionally stale. A manifest that declares this kind is accepted
     * and validated like any other -- schema, hashes, signature, ABI
     * where it applies -- but [PluginStore] refuses to activate it and
     * says why, rather than pretending a runner exists.
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

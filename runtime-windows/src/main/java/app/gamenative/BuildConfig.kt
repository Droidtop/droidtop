package app.gamenative

/**
 * Minimal, real compatibility shim for the two `app.gamenative.BuildConfig`
 * fields the forked-in `com.winlator.container.Container` actually reads
 * (`MODERN_XR`, `XR_BUILD`) -- droidtop doesn't have a Gradle-generated
 * `BuildConfig` under this package (its own module namespace is
 * `dev.droidtop.runtime.windows`), so this is a real, plain, hand-written
 * substitute rather than AGP's auto-generated one. Both false: droidtop
 * doesn't support Meta Quest/XR immersive mode, matching this module's
 * real, current scope.
 */
object BuildConfig {
    const val MODERN_XR: Boolean = false
    const val XR_BUILD: Boolean = false

    // Real field name (AGP always generates DEBUG for every module), used
    // by the forked PowerManager.kt to gate its own verbose logging --
    // hardcoded true here since this whole module is debug-only for now
    // (no release build type wired up), not read from Gradle's actual
    // build type the way AGP's real generated BuildConfig would.
    const val DEBUG: Boolean = true

    // Real, meaningful upstream flag (its own "modern"/"modernXr" product
    // flavors set this true, targetSdk 36, arm64-only; "legacy"/"legacyXr"
    // set it false, targetSdk 28, arm64+armeabi-v7a) -- ProcessHelper.java
    // uses it to decide whether to prefix spawned commands with
    // "/system/bin/linker64" for compatibility. Hardcoded false here
    // (the more conservative/compatible default) rather than guessed --
    // real per-device SDK-version wiring is future work once this module
    // actually spawns processes.
    const val MODERN_ANDROID: Boolean = false
}

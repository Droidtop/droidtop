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
}

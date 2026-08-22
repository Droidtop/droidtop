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
}

package com.droiddeck.launcher.stores

import android.util.Log

/**
 * The loader of DroidDeck's store download engines, `libdroiddeckstores.so` (docs/SPEC.md 7g, "Store engines from
 * DroidDeck"; mytop-project/tracker#335). Ported from DroidDeck (app/src/main/java/com/droiddeck/launcher/stores/
 * StoresNative.kt at cc63b40, GPL-3.0, see NOTICE.md).
 *
 * The engines are DroidDeck's Rust crate, built unchanged from the vendor/droiddeck submodule. Its JNI functions are
 * named for DroidDeck's classes (`Java_com_droiddeck_launcher_stores_StoresNative_nativeVersion` and the like), so the
 * classes that declare them live under DroidDeck's package names here, and hold nothing but the bindings: droidtop's
 * stores (dev.droidtop.stores) call them. That keeps the crate unpatched, a hook rather than a rewrite.
 */
object StoresNative {
    private const val TAG = "StoresNative"
    private const val LIBRARY = "droiddeckstores"

    @Volatile private var loaded: Boolean? = null
    @Volatile private var versionText: String? = null
    @Volatile private var failure: String? = null

    /** Whether the engines loaded. A build without them (a local build that skipped the script) answers false. */
    val available: Boolean get() = ensureLoaded()

    /** The engines' own version, once loaded. */
    val version: String? get() { ensureLoaded(); return versionText }

    /** Why the engines did not load, for diagnostics; null when they did. */
    val problem: String? get() { ensureLoaded(); return failure }

    @Synchronized
    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary(LIBRARY)
            versionText = nativeVersion()
            Log.i(TAG, "store engines ${versionText ?: "?"} loaded")
            true
        } catch (t: Throwable) {
            failure = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            Log.w(TAG, "store engines unavailable: $failure")
            false
        }
        loaded = ok
        return ok
    }

    @JvmStatic
    external fun nativeVersion(): String
}

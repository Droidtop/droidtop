package dev.droidtop.pluginhost

import android.os.ParcelFileDescriptor

/**
 * The plugin's own files in a plugin process loaded from descriptors (docs/plugin-api.md 5.3, "Guarded hooks";
 * `native/src/sandbox_hooks.c`). The process may open none of droidtop's files and may execute no data file, so a
 * plugin's native libraries, a Flutter engine and its app snapshot and assets, which all load by path, are handed over
 * as descriptors and given virtual paths under [ROOT]. [register] makes each one a local file of the sandbox library: an
 * open, stat or access of its path, by any library, is answered from its descriptor at the system-call level.
 * [ensureHooks] rewrites the dlopen imports of ART's native loader, and [hook] the Flutter engine's, so a library at a
 * virtual path is mapped from a private in-memory copy the isolated domain may execute, under its virtual name.
 */
internal object SandboxFiles {
    const val ROOT = "/droidtop-sandbox/"

    /** Where a plugin's own payload files appear. */
    const val PLUGIN = ROOT + "plugin/"

    /** Where a runtime droidtop downloaded (the Flutter engine) appears. */
    const val RUNTIME = ROOT + "runtime/"

    init {
        System.loadLibrary("droidtoppy")
    }

    // The system library that maps a plugin's native library by path: libnativeloader (System.load / loadLibrary).
    private val SYSTEM = arrayOf("/libnativeloader.so")

    @Volatile private var systemReport: String? = null

    @JvmStatic private external fun nativeRegister(path: String, fd: Int): Boolean

    @JvmStatic private external fun nativeHook(suffixes: Array<String>): String

    @JvmStatic private external fun nativeHideSymbols(suffix: String, names: Array<String>): String

    @JvmStatic private external fun nativeBridgeScreen(suffix: String): String

    /** Gives [fd] the virtual [path] (under [ROOT]); the descriptor becomes the hooks' and the library's own. */
    fun register(path: String, fd: ParcelFileDescriptor): Boolean = nativeRegister(path, fd.detachFd())

    /** Hooks the system libraries once per process; returns which ones and how many import slots, for the containment check. */
    @Synchronized
    fun ensureHooks(): String = systemReport ?: nativeHook(SYSTEM).also { systemReport = it }

    /** Hooks a library loaded later (the Flutter engine, which opens its app snapshot itself). */
    fun hook(vararg suffixes: String): String = nativeHook(arrayOf(*suffixes))

    /** Makes dlsym in the library [suffix] names answer null for [names]; only that library's dlsym import changes. */
    fun hideSymbols(suffix: String, vararg names: String): String = nativeHideSymbols(suffix, arrayOf(*names))

    /** Gives the library [suffix] names the screen bridge's stand-in window ([ScreenBridge], `screen_bridge.c`). */
    @Synchronized
    fun bridgeScreen(suffix: String): String = nativeBridgeScreen(suffix)

    /** What [ensureHooks] reported, or null before it ran. */
    fun report(): String? = systemReport
}

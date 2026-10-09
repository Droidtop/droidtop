package dev.droidtop.pluginhost

import android.os.ParcelFileDescriptor

/**
 * The guarded hooks of a contained plugin's isolated process (docs/plugin-api.md 5.3, "Guarded hooks";
 * `native/src/sandbox_hooks.c`). The process may open none of droidtop's files and may execute no data file, so a
 * plugin's native libraries, a Flutter engine and its app snapshot and assets, which all load by path, are handed over
 * as descriptors and given virtual paths under [ROOT]. [ensureHooks] rewrites the imports of the system libraries that
 * open by path (ART's native loader, libcore's file calls, the asset manager and its zip reader) so such a path is
 * answered from its descriptor, and a library is mapped from a private in-memory copy the isolated domain may execute.
 * Only registered paths answer; everything else reaches libc unchanged.
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

    // The system libraries that open a plugin's files by path: libnativeloader (System.load / loadLibrary maps the
    // library), libjavacore and libopenjdk (the class loader's findLibrary checks a file opens), libandroidfw and
    // libziparchive (AssetManager.addAssetPath reads an asset zip).
    private val SYSTEM = arrayOf("/libnativeloader.so", "/libjavacore.so", "/libopenjdk.so", "/libandroidfw.so", "/libziparchive.so")

    @Volatile private var systemReport: String? = null

    @JvmStatic private external fun nativeRegister(path: String, fd: Int): Boolean

    @JvmStatic private external fun nativeHook(suffixes: Array<String>): String

    /** Gives [fd] the virtual [path] (under [ROOT]); the descriptor becomes the hooks' own. */
    fun register(path: String, fd: ParcelFileDescriptor): Boolean = nativeRegister(path, fd.detachFd())

    /** Hooks the system libraries once per process; returns which ones and how many import slots, for the containment check. */
    @Synchronized
    fun ensureHooks(): String = systemReport ?: nativeHook(SYSTEM).also { systemReport = it }

    /** Hooks a library loaded later (the Flutter engine, which opens its app snapshot itself). */
    fun hook(vararg suffixes: String): String = nativeHook(arrayOf(*suffixes))

    /** What [ensureHooks] reported, or null before it ran. */
    fun report(): String? = systemReport
}

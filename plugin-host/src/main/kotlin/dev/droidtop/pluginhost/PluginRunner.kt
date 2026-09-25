package dev.droidtop.pluginhost

/**
 * One kind's execution strategy. [PluginKind.NATIVE_BUNDLE] gets
 * [NativePluginRunner]; [PluginKind.PYTHON] has no runner yet -- see
 * that enum entry's own doc comment for why -- and [forKind] returning
 * null is how [PluginCrashPolicy] and the call sites tell "not installed
 * yet" apart from "installed but broken".
 */
interface PluginRunner {
    /** Loads the plugin (already verified by [PluginBundleInstaller]) so [invoke] can be called. Returns false, never throws, on any failure. */
    suspend fun load(record: PluginRecord, installDir: String): Boolean

    fun unload(pluginId: String)

    /**
     * Runs one capability call under a watchdog timeout. Never throws:
     * a timeout, a dead process, or a plugin exception all come back as
     * [PluginResult.failure], and in every one of those cases the caller
     * (droidtop's own capability call sites, and [PluginCrashPolicy]) is
     * also told to disable the plugin -- see [PluginCrashPolicy.guard].
     */
    suspend fun invoke(pluginId: String, capability: PluginCapability, args: Map<String, String>): PluginResult

    companion object {
        /** The watchdog every runner enforces per call -- long enough for a real network fetch, short enough that a hung plugin can't freeze a screen droidtop owns. */
        const val CALL_TIMEOUT_MS = 15_000L

        const val MAX_RESULT_BYTES = 256 * 1024
    }
}

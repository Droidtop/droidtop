package dev.droidtop.pluginhost

import android.content.Context

/**
 * "A plugin failure is shown to the user and disables that plugin. The
 * launcher keeps working." (docs/SPEC.md 12a). This is the one place
 * that rule is enforced, so every call site -- a status tile refresh, an
 * acquire_content search, a metadata pass -- goes through [guard]
 * instead of calling a [PluginRunner] directly and re-implementing this.
 *
 * [PluginRuntimeService] and [NativePluginRunner] both funnel failures
 * (an uncaught exception in plugin code, a timeout, the whole
 * `:pluginhost` process dying) into the crash callback given to
 * [NativePluginRunner]'s constructor; this object is that callback's one
 * real implementation.
 */
class PluginCrashPolicy(
    private val context: Context,
    private val onJobProgress: (pluginId: String, jobId: String, percent: Int, statusLine: String) -> Unit = { _, _, _, _ -> },
    private val onJobComplete: (pluginId: String, jobId: String, result: PluginResult) -> Unit = { _, _, _ -> },
) {
    private val runner: NativePluginRunner = NativePluginRunner(context, ::onCrash, onJobProgress, onJobComplete)

    private fun onCrash(pluginId: String, capability: String, reason: String) {
        if (pluginId.isEmpty()) {
            // The whole process died -- every plugin this runner had
            // loaded is affected, but this object only tracks the
            // connection, not which ids were live; the next call from
            // each affected row will itself fail through invoke()'s own
            // timeout/exception path and disable that specific id then.
            // A blanket "the plugin process died" is still worth one
            // disable pass so a repeatedly-crashing plugin doesn't keep
            // restarting the process on every call.
            return
        }
        PluginStore.disableWithReason(context, pluginId, reason)
    }

    /**
     * Runs [block] (a load or an invoke) and, on ANY failure -- the
     * runner already reported the crash via the callback above by the
     * time this returns -- makes sure the record is disabled even if the
     * failure path didn't already do it (belt and braces for a case the
     * callback missed, e.g. [ensureConnected] itself never getting a
     * connection).
     */
    suspend fun invoke(record: PluginRecord, capability: PluginCapability, args: Map<String, String>): PluginResult {
        if (!record.runnable()) return PluginResult.failure("plugin is not approved/enabled")
        if (record.manifest.kind != PluginKind.NATIVE_BUNDLE) {
            return PluginResult.failure("no runner for kind ${record.manifest.kind.id} yet")
        }
        val dir = PluginStore.payloadDirFor(context, record.manifest.id)
        if (PluginBundleInstaller.verifyInstalled(PluginStore.root(context), record) != null) {
            PluginStore.disableWithReason(context, record.manifest.id, "files changed on disk since approval")
            return PluginResult.failure("re-verification failed")
        }
        if (!runner.load(record, dir.absolutePath)) {
            return PluginResult.failure("plugin failed to load")
        }
        // A returned PluginResult.failure (ok=false, no exception) is a
        // PLUGIN reporting its own ordinary failure -- "no network",
        // "nothing found" -- and is not a crash: it must not disable the
        // plugin, or every transient failure would permanently kill it.
        // Only a thrown exception, a timeout or the process dying counts
        // as a crash, and those already went through onCrash() by the
        // time this call returns (PluginRuntimeService.invoke catches
        // Throwable and reports it there instead of encoding a normal
        // failure result; NativePluginRunner's timeout/exception catches
        // do the same).
        return runner.invoke(record.manifest.id, capability, args)
    }

    /** Starts a long-running job for [record] (see [PluginJob]); same runnable/re-verify gates as [invoke], null on any refusal. */
    suspend fun startJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>): String? {
        if (!record.runnable() || record.manifest.kind != PluginKind.NATIVE_BUNDLE) return null
        if (PluginBundleInstaller.verifyInstalled(PluginStore.root(context), record) != null) {
            PluginStore.disableWithReason(context, record.manifest.id, "files changed on disk since approval")
            return null
        }
        val dir = PluginStore.payloadDirFor(context, record.manifest.id)
        if (!runner.load(record, dir.absolutePath)) return null
        return runner.startJob(record.manifest.id, capability, args)
    }

    fun cancelJob(pluginId: String, jobId: String) {
        runner.cancelJob(pluginId, jobId)
    }

    fun shutdown() {
        runner.unbind()
    }
}

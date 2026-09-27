package dev.droidtop.pluginhost

/**
 * What [PluginJobsCenter] needs from a job's own crash-contained runner.
 * [PluginCrashPolicy] is the one real implementation; this interface
 * exists so [PluginJobsCenter]'s own routing/filtering logic (which
 * job's progress/completion goes to which tracked [PluginJobsCenter.Entry],
 * including the exact race dq-pluginui-01 found and fixed 2026-09-27) can
 * be unit tested with a fake runner instead of a real Android Service /
 * RemoteCallbackList / binder connection -- see
 * `PluginJobsCenterTest.kt`.
 */
interface PluginJobRunner {
    /** Starts [capability]'s job for [record] under the given [jobId] (chosen by the caller -- see [IPluginRuntime.startJob]'s own doc comment for why). Returns false when refused outright. */
    suspend fun startJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String): Boolean

    fun cancelJob(pluginId: String, jobId: String)

    fun shutdown()
}

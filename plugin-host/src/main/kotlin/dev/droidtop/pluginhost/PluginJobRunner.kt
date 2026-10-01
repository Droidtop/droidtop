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
    val supportsCheckpointResume: Boolean get() = false
    /** Starts [capability]'s job for [record] under the given [jobId] (chosen by the caller -- see [IPluginRuntime.startJob]'s own doc comment for why). Returns false when refused outright. */
    suspend fun startJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String): Boolean

    /** Stops at the runner's next cooperative boundary; false means unsupported. */
    fun pauseJob(pluginId: String, jobId: String, resumePayload: String): Boolean = false

    /** Restarts a paused job with its last checkpoint; false means unsupported. */
    suspend fun resumeJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String, resumePayload: String): Boolean = false

    fun cancelJob(pluginId: String, jobId: String)

    fun shutdown()
}

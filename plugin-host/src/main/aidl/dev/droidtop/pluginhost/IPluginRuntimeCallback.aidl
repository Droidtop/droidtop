package dev.droidtop.pluginhost;

/**
 * Handed to {@link IPluginRuntime#setCallback} so the :pluginhost process
 * can report a crash back to :app without :app having to poll. This is
 * the other half of PluginCrashPolicy's containment: the binder
 * DeathRecipient catches the process dying outright, this callback
 * catches a plugin that threw but left the process alive.
 */
oneway interface IPluginRuntimeCallback {
    /** capability is empty ("") when the crash happened outside any one invoke() call, e.g. during load. */
    void onPluginCrashed(String pluginId, String capability, String reason);

    /** One progress tick for a job started with {@link IPluginRuntime#startJob}. percent is -1 for indeterminate. */
    void onJobProgress(String pluginId, String jobId, int percent, String statusLine);

    /** Fired exactly once per job, success or failure -- resultJson is the same shape {@link IPluginRuntime#invoke} returns. */
    void onJobComplete(String pluginId, String jobId, String resultJson);
}

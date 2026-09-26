package dev.droidtop.pluginhost;

import dev.droidtop.pluginhost.IPluginRuntimeCallback;

/**
 * The one binder contract between :app and the isolated :pluginhost
 * process (docs/SPEC.md 12a). Everything crosses as plain strings
 * (JSON in, JSON out) rather than a richer Parcelable, on purpose: the
 * boundary is untrusted-input-shaped either way ("droidtop treats what a
 * plugin returns as untrusted input"), so there is no value in a typed
 * Parcelable a malformed plugin could still violate, and a string payload
 * is trivially size-capped by the caller before it ever reaches this
 * interface (PluginRuntimeService rejects anything over the cap itself,
 * belt and braces).
 */
interface IPluginRuntime {
    void setCallback(IPluginRuntimeCallback callback);

    /**
     * Loads one plugin's already-validated, already-installed bundle.
     * installDir is this plugin's private per-plugin directory
     * (PluginStore.dataDirFor); entryClass is the manifest's declared
     * entry point. Validation (hashes, signature, ABI, schema) already
     * happened in :app's process before this is ever called -- this
     * method only loads code that has already passed every check in
     * PluginBundleInstaller.
     *
     * Returns false (never throws across the binder) when the class
     * can't be loaded or doesn't implement the plugin API; a plugin that
     * fails to load is disabled, exactly like one that crashes after
     * loading.
     *
     * rootApproved is PluginRecord.rootApproved at the moment :app
     * issued this load -- droidtop's own approval state never lives in
     * this process, so it is handed down on every load (see
     * PluginContext.hasRootApproval).
     */
    boolean loadPlugin(String pluginId, String installDir, String entryClass, boolean rootApproved);

    void unloadPlugin(String pluginId);

    /**
     * Calls one capability with a JSON-encoded argument bundle and
     * returns the plugin's JSON-encoded result, or null on any failure
     * (a timeout, an uncaught exception inside the plugin, a result over
     * the size cap). The caller (NativePluginRunner) applies its own
     * watchdog on top of this call in case the process itself hangs
     * rather than the call returning.
     */
    String invoke(String pluginId, String capability, String argsJson);

    /**
     * Starts a long-running job (docs/SPEC.md 12a's job shape) and
     * returns a jobId immediately, or null when the plugin doesn't
     * implement {@link DroidtopPlugin#startJob} or isn't loaded.
     * Progress and completion arrive later through
     * {@link IPluginRuntimeCallback#onJobProgress}/{@code onJobComplete}
     * -- this call itself is not bound by the per-call watchdog the way
     * {@link #invoke} is, since a real job is expected to run minutes.
     */
    String startJob(String pluginId, String capability, String argsJson);

    void cancelJob(String pluginId, String jobId);
}

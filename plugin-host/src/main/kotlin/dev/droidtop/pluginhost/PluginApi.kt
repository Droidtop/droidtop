package dev.droidtop.pluginhost

/**
 * What a `native_bundle` plugin implements, loaded by [PluginRuntimeService]
 * in the isolated :pluginhost process. One `invoke` per capability call,
 * JSON in and JSON out at the binder boundary ([dev.droidtop.pluginhost.IPluginRuntime]),
 * decoded to/from a [PluginArgs]/[PluginResult] pair here so plugin authors
 * write against typed maps, not raw strings.
 *
 * A plugin never receives a droidtop database handle, a ContentResolver
 * bound to droidtop's own providers, or a raw filesystem root -- only what
 * [PluginContext] hands it. That is the API surface itself, not an
 * incidental restriction: "Plugins never get handles to droidtop's
 * databases; they call the API" (docs/SPEC.md 12a).
 */
interface DroidtopPlugin {
    /** Called once after loading, before any [invoke]. Do the minimum here -- this call is still covered by the runner's watchdog. */
    fun onLoad(context: PluginContext) {}

    /** Called before the process unloads this plugin (disable, uninstall, or an update installing a new digest). Best-effort; the process may already be dying. */
    fun onUnload() {}

    /**
     * Handles one capability call. [capability] is always one this
     * plugin declared in its manifest -- [PluginRuntimeService] never
     * routes an undeclared one here. Throwing is fine and expected to
     * happen sometimes: [PluginCrashPolicy] catches it, reports it back
     * through [dev.droidtop.pluginhost.IPluginRuntimeCallback], and the
     * call that triggered it simply fails for its caller. A plugin must
     * not catch-and-swallow to "protect" itself from this; a thrown
     * exception here is the normal, supported way to report "this call
     * failed."
     */
    fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult

    /**
     * The long-running counterpart to [invoke] (see [PluginJob]). Default
     * throws, which [PluginRuntimeService.startJob] turns into a clean
     * "this plugin doesn't support jobs" rather than a crash -- most
     * plugins only ever implement [invoke]. A plugin that overrides this
     * MUST call [progress] on its own background thread/coroutine and
     * return quickly itself: this method's own return is just "the job
     * started", not "the job finished".
     */
    fun startJob(capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        throw UnsupportedOperationException("${this::class.simpleName} does not support long-running jobs")
    }

    /** Best-effort: asks a running job to stop. A plugin that ignores this still gets unloaded on disable/uninstall. */
    fun cancelJob(jobId: String) {}
}

/** What a plugin calls from inside [DroidtopPlugin.startJob] to report progress and, exactly once, completion. */
interface PluginJobProgress {
    fun report(percent: Int, statusLine: String)
    fun complete(result: PluginResult)
}

/** Read-only view of one call's arguments -- a thin wrapper so plugin code doesn't parse JSON by hand. */
class PluginArgs(private val values: Map<String, String>) {
    fun string(key: String): String? = values[key]
    fun stringOrDefault(key: String, default: String): String = values[key] ?: default
    fun keys(): Set<String> = values.keys
}

/** A capability call's answer. [values] is treated as untrusted by droidtop's side regardless of what a plugin puts in it (12a point 5): size-capped, schema-checked, never used as a path/URI/intent target directly. */
class PluginResult private constructor(val ok: Boolean, val values: Map<String, String>, val error: String?) {
    companion object {
        fun success(values: Map<String, String> = emptyMap()) = PluginResult(true, values, null)
        fun failure(reason: String) = PluginResult(false, emptyMap(), reason)
    }
}

/**
 * What droidtop hands a plugin instead of a database handle. Every
 * method here is itself capability-scoped and origin-scoped: a
 * `metadata_source`-only plugin calling [libraryFolder] gets nothing,
 * because the manifest never declared that capability.
 */
interface PluginContext {
    /** This plugin's own private directory (`filesDir/plugins/<id>/data`), separate from its read-only installed payload -- the "per-plugin data directory" the crash-containment decision requires. */
    fun privateDataDir(): String

    /**
     * The real, already-configured destination folder for one system, by
     * id -- present only when this plugin declared [PluginCapability.ACQUIRE_CONTENT]
     * and the call came from that system's own settings screen (the same
     * shape the JSON half's `{system.folder}` placeholder already uses,
     * §12). Never a writable handle into the whole library; the plugin
     * writes real files into a real folder with its own process's normal
     * filesystem permissions, same as `acquire_content`'s JSON form.
     */
    fun libraryFolderPath(systemId: String): String?

    /**
     * True only when BOTH the device actually has root available AND the
     * user approved this specific plugin's root request on the approval
     * screen ([PluginRecord.rootApproved]). A plugin that declared
     * `requestsRoot` must keep its core function working when this is
     * false -- root is an optional enhancement here, never something a
     * plugin may require to do its basic job (owner directive
     * 2026-09-25). droidtop's own launcher/handheld code still never
     * calls this; it exists only for this one, opt-in, plugin-level
     * exception to the standing non-root rule.
     */
    fun hasRootApproval(): Boolean

    /**
     * One shared answer to "is Shizuku available and has this device's
     * user granted it", so plugins that want a privileged call without
     * full root don't each reimplement the pairing/permission handshake
     * (cross-cutting need surfaced 2026-09-25 by more than one real
     * plugin design). Best-effort and read-only: checks whether
     * Shizuku's manager app is installed and whether its
     * `moe.shizuku.privileged.api.permission.API_V23` permission is
     * currently granted to droidtop -- the same lightweight check apps
     * commonly make without depending on Shizuku's own client library --
     * and returns false rather than throwing when Shizuku is absent,
     * unpaired, or the permission was revoked. A plugin still declares
     * [PluginManifest.requestsRoot] or [PluginManifest.boundServiceTargets]
     * for what it actually wants to do; this method only answers whether
     * the Shizuku path is available at all.
     */
    fun hasShizukuAccess(): Boolean
}

/**
 * The long-running-job shape a quick request/response `invoke()` can't
 * express -- a patch/install/scan that runs for minutes and reports
 * progress (cross-cutting need surfaced 2026-09-25). A plugin capability
 * that needs this starts a job (see [dev.droidtop.pluginhost.IPluginRuntime.startJob]),
 * gets a [jobId] back immediately, and droidtop's caller polls or
 * receives [dev.droidtop.pluginhost.IPluginRuntimeCallback]'s
 * job-progress/job-complete calls -- the plugin process is free to keep
 * running the job on its own thread between binder calls, unlike
 * `invoke()` which is bounded by [PluginRunner.CALL_TIMEOUT_MS] end to
 * end.
 */
data class PluginJob(
    val jobId: String,
    val done: Boolean,
    /** 0..100, or -1 when the job doesn't know a fraction (indeterminate). */
    val progressPercent: Int,
    val statusLine: String,
    val result: PluginResult?,
)

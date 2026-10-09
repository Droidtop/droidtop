package dev.droidtop.pluginhost

/**
 * What a `native_bundle` plugin implements, loaded in a plugin process of
 * its own ([PluginSandboxService] when contained, [PluginRuntimeService]
 * with full access). One `invoke` per capability call,
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
     * The contract 2 entry point (docs/plugin-api.md 1.3): one call in the
     * v2 envelope, for an extension point (`ui.context_action`), an event,
     * or `api:<id>` when this plugin is a provider serving another plugin.
     * The default answers through the contract 1 capability the point
     * replaced ([LegacyHandle]) and UNSUPPORTED otherwise, so a plugin that
     * only implements [invoke] keeps working. Throw for a crash; an
     * ordinary failure is [PluginReply.error].
     */
    fun handle(call: PluginCall): PluginReply = LegacyHandle.translate(this, call)

    /**
     * The long-running counterpart to [invoke] (see [PluginJob]). Default
     * throws, which [PluginRuntimeService.startJob] turns into a clean
     * "this plugin doesn't support jobs" rather than a crash -- most
     * plugins only ever implement [invoke]. A plugin that overrides this
     * MUST call [progress] on its own background thread/coroutine and
     * return quickly itself: this method's own return is just "the job
     * started", not "the job finished". [jobId] is the SAME id
     * [PluginRuntimeService.startJob] already generated and returned to
     * droidtop's caller -- handed to the plugin so a later [cancelJob]
     * can be correlated to a specific job when a plugin runs more than
     * one at a time, instead of every plugin having to invent its own
     * id and hope it lines up.
     */
    fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        throw UnsupportedOperationException("${this::class.simpleName} does not support long-running jobs")
    }

    /** Best-effort: asks a running job to stop. A plugin that ignores this still gets unloaded on disable/uninstall. */
    fun cancelJob(jobId: String) {}

    /**
     * The event-hook half of the API (docs/SPEC.md 12a "Event hooks"):
     * called when droidtop fires a [dev.droidtop.pluginhost.PluginEvent]
     * this plugin declared in [PluginManifest.subscribedEvents] --
     * [dev.droidtop.pluginhost.PluginRuntimeService] never delivers an
     * event this plugin didn't subscribe to, the same "never routes an
     * undeclared one here" guarantee [invoke] already has for
     * capabilities. Default is a no-op success, so a plugin that
     * subscribes to nothing (the default) is never affected by any event
     * regardless of what droidtop fires.
     *
     * Bounded by the same call watchdog as [invoke]
     * ([dev.droidtop.pluginhost.PluginRunner.CALL_TIMEOUT_MS]) -- this is
     * a quick "do you want to react" check, not itself a place to do
     * slow work. A plugin that wants to react with a real long-running
     * action (a core download, a patch) says so in its answer instead of
     * doing it here: see [dev.droidtop.pluginhost.PluginEvent]'s own doc
     * comment for the `startJob`-signaling shape.
     */
    fun onEvent(event: PluginEvent, args: PluginArgs): PluginResult = PluginResult.success()
}

/** What a plugin calls from inside [DroidtopPlugin.startJob] to report progress and, exactly once, completion. */
interface PluginJobProgress {
    fun report(percent: Int, statusLine: String)
    /** Reports an opaque, small checkpoint for a later restart. Null clears it. */
    fun checkpoint(percent: Int, statusLine: String, resumePayload: String?) = report(percent, statusLine)
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
    /**
     * `host.call` (docs/plugin-api.md 1.4): one call to droidtop's broker,
     * or through it to a provider plugin. [argsJson] is a JSON object; the
     * return value is a reply as JSON, `{"ok":true,"data":{...}}` or
     * `{"ok":false,"error":{"code","message"}}`. It never throws. The
     * methods below are this same call with fixed arguments, kept for
     * contract 1 plugins.
     */
    fun call(api: String, version: Int, op: String, argsJson: String): String

    /**
     * The same call for an op that hands over a file instead of JSON (`data.open`, `files.open`,
     * `files.shared.open`; docs/plugin-api.md 3 D4, D5, H1). [PluginFileReply.fd] is the file, or null when the
     * call was refused, and [PluginFileReply.reply] says why. The plugin owns the descriptor and closes it. A
     * contained plugin can open no file by path, so this is how a file reaches it.
     */
    fun openFile(api: String, version: Int, op: String, argsJson: String): PluginFileReply =
        PluginFileReply(PluginReply.error(PluginErrorCode.UNSUPPORTED, "this host hands over no files").encode(), null)

    /**
     * A full-trust plugin's own directory (`filesDir/plugins/<id>/data`), separate from its read-only installed
     * payload. A contained plugin gets an empty string: it can open no path at all, and the same directory is
     * reached through the `data` API instead (docs/plugin-api.md 3 H1).
     */
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
     * Shizuku's manager app is installed and whether Shizuku's own client
     * reports its binder present with droidtop allowed, falling back to
     * the `moe.shizuku.manager.permission.API_V23` permission being
     * granted to droidtop, and returns false rather than throwing when Shizuku is absent,
     * unpaired, or the permission was revoked. A plugin still declares
     * [PluginManifest.requestsRoot] or [PluginManifest.boundServiceTargets]
     * for what it actually wants to do; this method only answers whether
     * the Shizuku path is available at all.
     */
    fun hasShizukuAccess(): Boolean

    /**
     * True when [packageName] is currently installed on this device --
     * the same lightweight `PackageManager` presence check
     * [hasShizukuAccess] already makes for Shizuku's own package,
     * generalised so an `app_status` plugin managing some OTHER
     * installed app can tell "not installed" apart from "installed but
     * not doing what I need" without ever being handed a raw
     * `PackageManager`/`Context` (cross-cutting need surfaced by more
     * than one real `app_status` plugin design). Never throws; returns
     * false for an unknown package.
     */
    fun isAppInstalled(packageName: String): Boolean

    /**
     * Launches [packageName]'s own default launcher activity, exactly as
     * tapping its icon would -- the one generic way an `app_status`
     * plugin hands the user off to another app's own UI (its setup
     * screen, a pairing flow, its own settings) without droidtop or the
     * plugin needing to know that app's activity names, and without the
     * plugin holding a `Context` of its own to build the `Intent`
     * itself. Returns false, never throws, when [packageName] isn't
     * installed or declares no launcher activity.
     */
    fun launchApp(packageName: String): Boolean

    /**
     * Launches [packageName]'s own default launcher activity like
     * [launchApp], but with [extras] attached as String extras and,
     * optionally, [action] overriding the intent's action -- the
     * generalisation an `app_status` plugin needs when the other app
     * documents its own Intent-extra launch contract (e.g. RetroArch's
     * `ROM`/`LIBRETRO`/`CONFIGFILE` extras, read directly from its own
     * source rather than guessed) and there is no other supported way to
     * aim that launch at a specific config, since [launchApp] alone
     * cannot attach anything to the Intent it builds.
     *
     * Still never hands the plugin a `Context` or an `Intent` object --
     * it stays a data-in call across the same boundary [launchApp]
     * already crosses, so the trust shape is identical: a plugin picks
     * string key/value pairs, droidtop's own process builds and fires
     * the actual `Intent`. Returns false, never throws, when
     * [packageName] isn't installed or declares no launcher activity.
     */
    fun launchAppWithExtras(packageName: String, extras: Map<String, String>, action: String? = null): Boolean
}

/** What [PluginContext.openFile] returns: the broker's reply JSON, and the file when the call was allowed. */
class PluginFileReply(val reply: String, val fd: android.os.ParcelFileDescriptor?)

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

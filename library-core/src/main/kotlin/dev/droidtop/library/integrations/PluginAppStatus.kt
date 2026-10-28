package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a plugin's declared [PluginCapability.APP_STATUS] as a real
 * screen (docs/SPEC.md 12a: "status and actions for ONE OTHER INSTALLED
 * APP the plugin manages"). First real UI caller of this capability.
 *
 * **The wire contract** (docs/plugin-api.md 3 C, `apps.bridge`; Droidtop/tracker#544 item 5, #521 item 4):
 * `invoke(APP_STATUS, {"action": "status"})` -> `PluginResult.success` with these documented fields:
 * - `package`: the managed app's package name. droidtop reads the app's own name and whether it is installed from
 *   Android itself, so a plugin cannot get either wrong;
 * - `installed` ("true"/"false"): only when the plugin knows better than Android (an app it installs some other way);
 *   without it droidtop asks Android about `package`;
 * - `status`: one plain sentence about the app's state for the person ("Running, 3 cores downloaded");
 * - `version`: the app's version, when the plugin knows it and Android does not say;
 * - optionally `job`, `jobArgKey` and `jobLabel`: one free-text job (below).
 * Any other value is never put on the page as "Key: value"; it is kept behind "Technical details".
 * `job`, `jobArgKey` and `jobLabel`: when a plugin offers
 *   ONE free-text-argument job from this screen (e.g. "download a named
 *   core"), it names the job, the single arg key the job takes, and the
 *   button label; droidtop renders exactly one generic text-entry action
 *   that calls `startJob(APP_STATUS, {"job": job, jobArgKey: <the text
 *   the user typed>})`, tracked through [PluginJobsCenter] like any
 *   other job. A plugin that doesn't offer one simply omits these three
 *   keys.
 *
 * "Launch" is offered whenever `installed` is true. For an app Android knows
 * (`package` installed, with a launcher entry) droidtop opens it itself; only
 * otherwise does it send `invoke(APP_STATUS, {"action": "launch"})`, and a
 * plugin returning a failure just shows that failure.
 */
object PluginAppStatus {
    /** The fields droidtop reads itself; anything else a plugin sends is technical detail. */
    private val DOCUMENTED = setOf("installed", "package", "status", "version", "job", "jobArgKey", "jobLabel")

    /** What droidtop knows about [pkg] from Android: its name and version, or null when it is not installed. */
    private fun androidApp(context: Context, pkg: String): Pair<String, String?>? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(pkg, 0)
        val name = info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
        name to info.versionName
    }.getOrNull()

    /**
     * Every installed+approved [PluginCapability.APP_STATUS] plugin
     * whose OWN status call currently reports it manages [packageName].
     * On-demand only (one settings-screen open, e.g. a system's Player
     * choice screen for one specific installed emulator) -- never called
     * from list rendering (docs/SPEC.md performance rule: "no per-game
     * disk lookups in list rendering", the same reasoning extended to
     * "no per-plugin binder call per drawer icon").
     */
    suspend fun sourcesFor(context: Context, packageName: String): List<PluginRecord> {
        val candidates = PluginStore.runnableFor(context, PluginCapability.APP_STATUS)
        if (candidates.isEmpty()) return emptyList()
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            candidates.filter { record ->
                val result = policy.invoke(record, PluginCapability.APP_STATUS, mapOf("action" to "status"))
                result.ok && result.values["package"] == packageName
            }
        } finally {
            policy.shutdown()
        }
    }

    /** The generic app_status screen for one plugin. */
    fun screenFor(record: PluginRecord): CatalogScreen {
        val m = record.manifest
        return CatalogScreen(
            id = "plugin_app_status_${m.id}",
            title = m.label,
            subtitle = "App status from \"${m.label}\", read live each time this screen opens",
            groups = { context ->
                val policy = PluginCrashPolicy(context.applicationContext)
                val result = try {
                    policy.invoke(record, PluginCapability.APP_STATUS, mapOf("action" to "status"))
                } finally {
                    policy.shutdown()
                }
                if (!result.ok) {
                    return@CatalogScreen listOf(
                        CatalogGroup(
                            id = "plugin_app_status_${m.id}_error",
                            title = null,
                            items = listOf(ActionItem(id = "plugin_app_status_${m.id}_error_row", title = result.error ?: "Couldn't read this plugin's app status")),
                        ),
                    )
                }
                val pkg = result.values["package"]?.takeIf { it.isNotBlank() }
                val app = pkg?.let { androidApp(context, it) }
                // The plugin's own `installed` wins only when it gave one; otherwise Android answers for the package.
                val installed = result.values["installed"]?.toBooleanStrictOrNull() ?: (app != null)
                val version = result.values["version"]?.takeIf { it.isNotBlank() } ?: app?.second
                val status = result.values["status"]?.trim()?.takeIf { it.isNotEmpty() }
                val technical = (result.values.keys - DOCUMENTED).sorted().map { "$it: ${result.values.getValue(it)}" }
                listOf(
                    CatalogGroup(
                        id = "plugin_app_status_${m.id}_status",
                        title = null,
                        items = buildList {
                            add(
                                ActionItem(
                                    id = "plugin_app_status_${m.id}_summary",
                                    title = app?.first ?: pkg ?: "The app ${m.label} manages",
                                    subtitle = status,
                                    value = if (installed) "Installed" else "Not installed",
                                ),
                            )
                            if (installed && version != null) {
                                add(ActionItem(id = "plugin_app_status_${m.id}_version", title = "Version", value = version))
                            }
                            // Undocumented values never reach the page as "Key: value" (Droidtop/tracker#544 item 5).
                            // A Developer menu row (docs/SPEC.md 7, "Developer menu").
                            if (technical.isNotEmpty() && dev.droidtop.library.settings.DeveloperMenu.on(context)) {
                                add(
                                    dev.droidtop.library.settings.NestedScreenItem(
                                        id = "plugin_app_status_${m.id}_technical",
                                        title = "Technical details",
                                        subtitle = "What ${m.label} reported",
                                        inline = CatalogScreen(
                                            id = "plugin_app_status_${m.id}_technical_screen",
                                            title = "Technical details",
                                            groups = {
                                                listOf(CatalogGroup(id = "plugin_app_status_${m.id}_technical_rows", title = null, items = technical.mapIndexed { i, line ->
                                                    ActionItem(id = "plugin_app_status_${m.id}_technical_$i", title = line)
                                                }))
                                            },
                                        ),
                                    ),
                                )
                            }
                            if (installed) {
                                add(
                                    AsyncActionItem(
                                        id = "plugin_app_status_${m.id}_launch",
                                        title = "Launch",
                                        subtitle = "Opens the app this plugin manages",
                                        run = { ctx, _ ->
                                            // An app Android knows is opened by droidtop itself, the way it opens any app; the
                                            // plugin is asked only for one it installs some other way (Droidtop/tracker#558:
                                            // the Shizuku plugin answers no "launch" action).
                                            val intent = pkg?.takeIf { app != null }?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                                            if (intent != null) {
                                                withContext(Dispatchers.Main) { dev.droidtop.library.LaunchDisplay.start(ctx, intent) }
                                                "Opened ${app?.first ?: pkg}"
                                            } else {
                                                val launchPolicy = PluginCrashPolicy(ctx.applicationContext)
                                                try {
                                                    val launchResult = launchPolicy.invoke(record, PluginCapability.APP_STATUS, mapOf("action" to "launch"))
                                                    if (launchResult.ok) "Launched" else (launchResult.error ?: "Couldn't launch")
                                                } finally {
                                                    launchPolicy.shutdown()
                                                }
                                            }
                                        },
                                    ),
                                )
                            }
                            val job = result.values["job"]
                            val jobArgKey = result.values["jobArgKey"]
                            if (job != null && jobArgKey != null) {
                                val jobLabel = result.values["jobLabel"] ?: "Run \"$job\""
                                add(
                                    TextInputItem(
                                        id = "plugin_app_status_${m.id}_job_$job",
                                        title = jobLabel,
                                        subtitle = "Type a value and commit to start this job; progress shows on the Jobs screen",
                                        value = "",
                                        onChange = { ctx, text ->
                                            if (text.isNotBlank()) {
                                                PluginJobsCenter.start(
                                                    context = ctx,
                                                    record = record,
                                                    capability = PluginCapability.APP_STATUS,
                                                    args = mapOf("job" to job, jobArgKey to text.trim()),
                                                    // No plugin-label prefix here: PluginJobsScreen
                                                    // already prepends "<pluginLabel>: " to every
                                                    // entry's title -- doing it here too produced a
                                                    // doubled "RetroArch manager: RetroArch manager:
                                                    // download_core (snes9x)" row on the rig
                                                    // (dq-pluginui-01).
                                                    title = "$job (${text.trim()})",
                                                )
                                            }
                                        },
                                    ),
                                )
                            }
                        },
                    ),
                )
            },
        )
    }
}

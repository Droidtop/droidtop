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

/**
 * Renders a plugin's declared [PluginCapability.APP_STATUS] as a real
 * screen (docs/SPEC.md 12a: "status and actions for ONE OTHER INSTALLED
 * APP the plugin manages"). First real UI caller of this capability.
 *
 * **The wire contract** (deliberately generic -- no plugin-specific
 * field names, same rule [AcquireContentSources] already follows for
 * `acquire_content`): `invoke(APP_STATUS, {"action": "status"})` ->
 * `PluginResult.success`, where droidtop reads:
 * - `installed` ("true"/"false") and `package` (the detected package
 *   name) -- droidtop's own summary line, and how [sourcesFor] matches a
 *   plugin to a specific installed app;
 * - every OTHER value is shown as its own read-only detail row, same
 *   "plugin writes a complete sentence, droidtop just displays it"
 *   convention [PluginSettingsRows] uses;
 * - optionally `job`, `jobArgKey` and `jobLabel`: when a plugin offers
 *   ONE free-text-argument job from this screen (e.g. "download a named
 *   core"), it names the job, the single arg key the job takes, and the
 *   button label; droidtop renders exactly one generic text-entry action
 *   that calls `startJob(APP_STATUS, {"job": job, jobArgKey: <the text
 *   the user typed>})`, tracked through [PluginJobsCenter] like any
 *   other job. A plugin that doesn't offer one simply omits these three
 *   keys.
 *
 * `invoke(APP_STATUS, {"action": "launch"})` is offered as a plain
 * "Launch" action whenever `installed` is true -- every `app_status`
 * plugin is expected to support it (droidtop never enforces this by
 * signature; a plugin returning a failure just shows that failure).
 */
object PluginAppStatus {
    /** "downloadedCoreCount" -> "Downloaded core count" -- best-effort camelCase splitter, only used for the generic detail rows above. */
    private fun humanizeKey(key: String): String {
        val spaced = key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase()
        return spaced.replaceFirstChar { it.uppercase() }
    }

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
                            items = listOf(ActionItem(id = "plugin_app_status_${m.id}_error_row", title = result.error ?: "Couldn't read this plugin's app status", run = {})),
                        ),
                    )
                }
                val installed = result.values["installed"] == "true"
                val pkg = result.values["package"]
                val detailKeys = result.values.keys - setOf("installed", "package", "job", "jobArgKey", "jobLabel")
                listOf(
                    CatalogGroup(
                        id = "plugin_app_status_${m.id}_status",
                        title = null,
                        items = buildList {
                            add(
                                ActionItem(
                                    id = "plugin_app_status_${m.id}_summary",
                                    title = if (installed) "Installed${pkg?.let { " ($it)" } ?: ""}" else "Not installed",
                                    run = {},
                                ),
                            )
                            detailKeys.sorted().forEach { key ->
                                add(
                                    ActionItem(
                                        id = "plugin_app_status_${m.id}_$key",
                                        // A bare value with no label read as an
                                        // unlabelled "0"/"false" row on the rig
                                        // (dq-pluginui-01, 2026-09-27) -- humanize
                                        // the plugin's own camelCase key so every
                                        // row reads as a full sentence, matching
                                        // PluginSettingsRows' "plugin writes a
                                        // complete sentence" convention as closely
                                        // as a single opaque value can.
                                        title = "${humanizeKey(key)}: ${result.values.getValue(key)}",
                                        run = {},
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
                                            val launchPolicy = PluginCrashPolicy(ctx.applicationContext)
                                            try {
                                                val launchResult = launchPolicy.invoke(record, PluginCapability.APP_STATUS, mapOf("action" to "launch"))
                                                if (launchResult.ok) "Launched" else (launchResult.error ?: "Couldn't launch")
                                            } finally {
                                                launchPolicy.shutdown()
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

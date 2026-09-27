package dev.droidtop.library.integrations

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord

/**
 * Renders a plugin's declared [PluginCapability.SETTINGS_ROWS] as real
 * rows in droidtop's own settings style (docs/SPEC.md 12a: "rows
 * rendered in droidtop's own settings style ... never a plugin-drawn
 * UI"). First real UI caller of this capability anywhere in droidtop.
 *
 * **The wire contract**: `invoke(SETTINGS_ROWS, {"target": target})` ->
 * `PluginResult.success(values = <a flat map>)`, where every value is
 * shown as its own read-only row -- a plugin's key is never shown (it
 * only lets a plugin tell its own rows apart across calls), only its
 * value, which the plugin is expected to write as a complete,
 * human-readable sentence -- exactly the shape
 * `droidtop-plugin-retroarch`'s `RetroArchPlugin.handleSettingsRows`
 * already ships ("RetroArch: installed (com.retroarch.aarch64)", not a
 * bare "installed"). Kept intentionally simple rather than inventing a
 * richer per-row schema (actions, toggles): droidtop's own settings
 * model ([dev.droidtop.library.settings.CatalogItem]) already covers
 * that ground for droidtop's OWN settings, and no installed plugin has
 * needed more than read-only rows yet.
 *
 * [target] identifies WHERE this call is being rendered -- [TARGET_GLOBAL]
 * for the plugin's own entry under Settings > App integrations >
 * Plugins, or [systemTarget] for a system's own settings screen -- so a
 * plugin that cares can tailor its answer (docs/SPEC.md 12a: "in the
 * relevant system or app settings where the plugin targets them"). A
 * plugin that ignores the arg (every plugin today) returns the same
 * rows regardless of where they're shown.
 */
object PluginSettingsRows {
    const val TARGET_GLOBAL = "global"

    fun systemTarget(systemId: String): String = "system:$systemId"

    fun screenFor(record: PluginRecord, target: String = TARGET_GLOBAL): CatalogScreen {
        val m = record.manifest
        return CatalogScreen(
            id = "plugin_settings_rows_${m.id}_$target",
            title = m.label,
            subtitle = "Settings rows from \"${m.label}\", read live each time this screen opens",
            groups = { context ->
                val policy = PluginCrashPolicy(context.applicationContext)
                try {
                    val result = policy.invoke(record, PluginCapability.SETTINGS_ROWS, mapOf("target" to target))
                    listOf(
                        CatalogGroup(
                            id = "plugin_settings_rows_${m.id}_group",
                            title = null,
                            items = if (!result.ok) {
                                listOf(ActionItem(id = "plugin_settings_rows_${m.id}_error", title = result.error ?: "Couldn't read this plugin's settings rows", run = {}))
                            } else if (result.values.isEmpty()) {
                                listOf(ActionItem(id = "plugin_settings_rows_${m.id}_empty", title = "\"${m.label}\" has nothing to show here", run = {}))
                            } else {
                                result.values.entries.sortedBy { it.key }.map { (key, value) ->
                                    ActionItem(id = "plugin_settings_rows_${m.id}_$key", title = value, run = {})
                                }
                            },
                        ),
                    )
                } finally {
                    policy.shutdown()
                }
            },
        )
    }
}

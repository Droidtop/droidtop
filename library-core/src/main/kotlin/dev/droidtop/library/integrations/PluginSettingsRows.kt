package dev.droidtop.library.integrations

import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.PluginRecord
import org.json.JSONObject

/**
 * A plugin's settings page: the one entry every way into a plugin's settings uses (its panel, its row under
 * Settings, Plugins, and a source's search). droidtop draws it in its own settings style (docs/SPEC.md 12a: "rows
 * rendered in droidtop's own settings style ... never a plugin-drawn UI"), from the plugin's `settings.page` facets
 * (docs/plugin-api.md 1.10) or else its `ui.settings` view (docs/plugin-api.md 1.6).
 *
 * [target] says WHERE the page is shown -- [TARGET_GLOBAL] for the plugin's own entry, sent to the plugin as
 * `"plugin"`, or [systemTarget] for a system's own settings screen -- so a plugin that cares can tailor its answer
 * (docs/SPEC.md 12a: "in the relevant system or app settings where the plugin targets them").
 */
object PluginSettingsRows {
    const val TARGET_GLOBAL = "global"
    const val UI_SETTINGS = "ui.settings"

    fun systemTarget(systemId: String): String = "system:$systemId"

    /**
     * True when [record] has a settings page: facets for `settings.page` (docs/plugin-api.md 1.10) or a
     * `ui.settings` view. The one test every way into a plugin's settings uses; [screenFor] is only for a plugin it
     * holds for.
     */
    fun has(record: PluginRecord): Boolean =
        PluginFacets.declares(record, PluginFacets.SETTINGS_PAGE) || record.manifest.v2.provides.any { it.point == UI_SETTINGS }

    fun screenFor(record: PluginRecord, target: String = TARGET_GLOBAL): CatalogScreen {
        val m = record.manifest
        // Facets first (docs/plugin-api.md 1.10): a plugin that declares settings.page is drawn from them, never from
        // its old view.
        if (PluginFacets.declares(record, PluginFacets.SETTINGS_PAGE)) {
            return PluginViews.screen(
                record = record,
                point = PluginFacets.POINT,
                op = PluginFacets.OP_GET,
                id = "plugin_settings_facets_${m.id}_$target",
                title = "${m.label} settings",
                args = PluginFacets.getArgs(PluginFacets.SETTINGS_PAGE),
                hostContext = JSONObject().put("target", if (target == TARGET_GLOBAL) "plugin" else target),
            )
        }
        // Else the plugin's own view (docs/plugin-api.md 1.6): inputs, actions and jobs, drawn by droidtop.
        return PluginViews.screen(
            record = record,
            point = UI_SETTINGS,
            op = "view",
            id = "plugin_settings_view_${m.id}_$target",
            title = "${m.label} settings",
            hostContext = JSONObject().put("target", if (target == TARGET_GLOBAL) "plugin" else target),
        )
    }
}

package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.pluginhost.DeclaredSetting
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginSettings
import dev.droidtop.pluginhost.SourceFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A plugin's settings page: the one entry every way into a plugin's settings uses (its panel, its row under
 * Settings, Plugins, and a source's search). droidtop draws it in its own settings style (docs/SPEC.md 12a: "rows
 * rendered in droidtop's own settings style ... never a plugin-drawn UI").
 *
 * Declared settings (Droidtop/tracker#880, docs/plugin-api.md 1.10). The owner: "The plugin should have exposed a
 * Settings facet, or exposed things in advance. It should not have to be asked or loaded for any of it". The page is
 * drawn from the manifest's `settings` and droidtop's own store ([PluginSettings]), so opening it never starts or
 * calls the plugin. A value the person sets is kept by droidtop and told to the plugin the next time it runs; an
 * action row runs the plugin's job, because the person pressed it. A plugin with no declaration but an older
 * `ui.settings` view is still drawn from that view (docs/plugin-api.md 1.6), until it declares its settings.
 */
object PluginSettingsRows {
    const val TARGET_GLOBAL = "global"
    const val UI_SETTINGS = "ui.settings"

    fun systemTarget(systemId: String): String = "system:$systemId"

    /** True when [record] has a settings page: declared settings, or an older `ui.settings` view. */
    fun has(record: PluginRecord): Boolean =
        record.manifest.v2.settings.isNotEmpty() || record.manifest.v2.provides.any { it.point == UI_SETTINGS }

    fun screenFor(record: PluginRecord, target: String = TARGET_GLOBAL): CatalogScreen {
        val m = record.manifest
        if (m.v2.settings.isNotEmpty()) {
            return CatalogScreen(id = "plugin_settings_${m.id}_$target", title = "${m.label} settings", groups = { ctx -> declaredGroups(ctx, record) })
        }
        // An older plugin's own view (docs/plugin-api.md 1.6): inputs, actions and jobs, drawn by droidtop, asked of the plugin.
        return PluginViews.screen(
            record = record,
            point = UI_SETTINGS,
            op = "view",
            id = "plugin_settings_view_${m.id}_$target",
            title = "${m.label} settings",
            hostContext = JSONObject().put("target", if (target == TARGET_GLOBAL) "plugin" else target),
        )
    }

    /** The states a row's `when` can name, read once per page: no plugin is called. */
    data class States(val signedIn: Boolean, val computerPaired: Boolean, val granted: Set<String>)

    /** Whether [condition] (a declared `when`, or none) holds in [states]. Pure. */
    fun holds(condition: String?, states: States): Boolean {
        condition ?: return true
        val negated = condition.startsWith("not ")
        val name = condition.removePrefix("not ")
        val value = when {
            name == "web.session.signed_in" -> states.signedIn
            name == "computer.paired" -> states.computerPaired
            name.startsWith("permission:") -> name.removePrefix("permission:") in states.granted
            else -> false
        }
        return value != negated
    }

    private suspend fun declaredGroups(context: Context, record: PluginRecord): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val store = PluginSettings(context)
        val values = store.values(record)
        val grants = PluginGrants.forContext(context).read(record.manifest.id)
        val states = States(
            signedIn = runCatching { dev.droidtop.pluginhost.PluginWebAccount.of(context, record)?.signedIn == true }.getOrDefault(false),
            computerPaired = runCatching { dev.droidtop.net.peer.Computers.list(context).isNotEmpty() }.getOrDefault(false),
            granted = record.manifest.v2.permissions.map { it.id }.filterTo(HashSet()) { PluginGrants.stateOf(record, grants, it) == GrantState.GRANTED },
        )
        val items = record.manifest.v2.settings.filter { holds(it.whenCondition, states) }.map { row -> item(record, row, values[row.id], store.text(record.manifest.id, row.id)) }
        listOf(CatalogGroup(id = "plugin_settings_${record.manifest.id}", title = null, items = items))
    }

    private fun item(record: PluginRecord, row: DeclaredSetting, value: Any?, pushed: PluginSettings.Text?): CatalogItem {
        val id = "plugin_setting_${record.manifest.id}_${row.id}"
        val sub = pushed?.sub ?: row.sub
        fun keep(ctx: Context, v: Any) {
            PluginSettings(ctx).set(record, row.id, v)?.let { dev.droidtop.library.settings.Notices.flash("${row.label}: $it", context = ctx) }
        }
        return when (row.kind) {
            DeclaredSetting.Kind.INPUT -> when (row.input) {
                "toggle" -> ToggleItem(id = id, title = row.label, subtitle = sub, current = value as? Boolean ?: false, onToggle = { ctx, on ->
                    withContext(Dispatchers.IO) { keep(ctx, on) }
                })
                "choice" -> ChoiceItem(
                    id = id, title = row.label, subtitle = sub,
                    options = row.choices.map { (v, l) -> ChoiceOption(v, l) },
                    current = value as? String,
                    onSelect = { ctx, v -> Thread { keep(ctx, v) }.apply { name = "plugin-setting"; isDaemon = true }.start() },
                )
                else -> TextInputItem(id = id, title = row.label, subtitle = sub, value = value as? String ?: "", onChange = { ctx, v ->
                    withContext(Dispatchers.IO) { keep(ctx, v) }
                })
            }
            DeclaredSetting.Kind.ACTION -> AsyncActionItem(
                id = id, title = row.label, subtitle = sub, value = pushed?.value,
                run = { ctx, onStatus ->
                    val result = PluginViews.runJob(ctx, record, UI_SETTINGS, row.op!!, JSONObject(), row.label, onStatus)
                    if (result.ok) result.values["summary"] ?: result.values["message"] ?: "Done" else "${record.manifest.label}: ${SourceFailures.plain(result.error)}"
                },
            )
            DeclaredSetting.Kind.INFO -> ActionItem(id = id, title = row.label, subtitle = sub, value = pushed?.value)
        }
    }
}

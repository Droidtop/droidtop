package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.HostWindowsApis
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.pluginhost.PluginRecord
import org.json.JSONObject

/**
 * Plugin facets on Desktop's bar (docs/plugin-api.md 1.12, Droidtop/tracker#221): the tray (`desktop.tray`, about the
 * device) and a window's menu (`desktop.window`, about that window). Each is drawn by the one renderer, as a page opened
 * from the bar, so nothing on the bar waits on a plugin to draw: the bar shows the plugin's name from its manifest, and
 * the plugin is asked only when the person opens it. Reads manifests and grants only; call off the main thread.
 */
object DesktopPluginFacets {
    const val TRAY = "desktop.tray"
    const val WINDOW = "desktop.window"

    /** The running plugins with a tray entry in Desktop, by name. */
    fun trayPlugins(context: Context): List<PluginRecord> = declaring(context, TRAY)

    /**
     * The running plugins with entries for a window of [appId]: each declares `desktop.window`, holds `windows.read`
     * as granted, and that permission reaches [appId]. A plugin is never asked about a window it may not see.
     */
    fun windowPlugins(context: Context, appId: String): List<PluginRecord> {
        val grants = PluginGrants.forContext(context)
        return declaring(context, WINDOW).filter { record ->
            PluginGrants.stateOf(record, grants.read(record.manifest.id), HostWindowsApis.READ) == GrantState.GRANTED &&
                HostWindowsApis.allowed(record, HostWindowsApis.READ, appId)
        }
    }

    /** The subject of a window's `get`: droidtop's id for it and its app id (docs/plugin-api.md 1.10, "The envelope"). */
    fun windowSubject(windowId: Long, appId: String): JSONObject =
        JSONObject().put("type", "window").put("windowId", windowId).put("appId", appId)

    /** [record]'s tray entry as a page: its `desktop.tray` facets about the device. */
    fun trayScreen(record: PluginRecord): CatalogScreen = PluginViews.screen(
        record = record,
        point = PluginFacets.POINT,
        op = PluginFacets.OP_GET,
        id = "plugin_desktop_tray_${record.manifest.id}",
        title = record.manifest.label,
        args = PluginFacets.getArgs(TRAY, PluginFacets.DEVICE_SUBJECT),
        hostContext = JSONObject().put("target", TRAY),
    )

    /** [record]'s entries for one window as a page, titled with the window's title: its `desktop.window` facets about it. */
    fun windowScreen(record: PluginRecord, windowId: Long, appId: String, windowTitle: String): CatalogScreen = PluginViews.screen(
        record = record,
        point = PluginFacets.POINT,
        op = PluginFacets.OP_GET,
        id = "plugin_desktop_window_${record.manifest.id}_$windowId",
        title = "${record.manifest.label}: $windowTitle",
        args = PluginFacets.getArgs(WINDOW, windowSubject(windowId, appId)),
        hostContext = JSONObject().put("target", WINDOW),
    )

    private fun declaring(context: Context, target: String): List<PluginRecord> =
        providersOf(context, PluginFacets.POINT, PluginModes.DESKTOP)
            .filter { (record, entry) ->
                target in dev.droidtop.pluginhost.FacetGuards.declaredTargets(listOf(entry)) && PluginFacets.shows(context, record, target)
            }
            .map { it.first }
            .distinctBy { it.manifest.id }
            .sortedBy { it.manifest.label.lowercase() }
}

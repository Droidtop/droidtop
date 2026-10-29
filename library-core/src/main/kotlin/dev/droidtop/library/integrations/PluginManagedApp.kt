package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeAction
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginRecord
import org.json.JSONObject

/**
 * The app a bridge plugin manages (docs/plugin-api.md 3 F1, `apps.bridge`; Droidtop/tracker#520), read from its manifest
 * so no panel calls the plugin to learn it: the `apps.bridge` entry's `app` (its name), `packages` and `get` (an https
 * page to get it from), or else the packages its `apps.check` permission declares. When none of the packages is
 * installed, droidtop says so once, the same way for every plugin: a notice at the top of the plugin's panel with Get,
 * and the panel's rows and tiles marked `needs: "app"` drawn inert with the reason. No plugin draws its own
 * "not installed" rows.
 */
object PluginManagedApp {
    /** The `needs` value a node or tile names when it only works with the managed app installed. */
    const val NEEDS = "app"

    data class App(val name: String?, val packages: List<String>, val getUrl: String?) {
        /** How the app is named to the person: its own name, or "Its app" when the plugin gave none. */
        val shownName: String get() = name ?: "Its app"
    }

    /** The app [record] manages, or null for a plugin that bridges none or names no package. Manifest only. */
    fun of(record: PluginRecord): App? {
        val m = record.manifest
        val entry = m.v2.provides.firstOrNull { it.point == "apps.bridge" }
        val bridges = entry != null || PluginCapability.APP_STATUS in m.capabilities
        if (!bridges) return null
        val extra = entry?.let { runCatching { JSONObject(it.extra) }.getOrNull() } ?: JSONObject()
        val declared = extra.optJSONArray("packages")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        val checked = m.v2.permissions.firstOrNull { it.id == "apps.check" }
            ?.let { runCatching { JSONObject(it.extra).optJSONArray("packages") }.getOrNull() }
            ?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        val packages = (declared.ifEmpty { checked }).filter { it.isNotBlank() && it != "*" }
        if (packages.isEmpty()) return null
        return App(
            name = extra.optString("app").trim().takeIf { it.isNotEmpty() }?.take(40),
            packages = packages,
            getUrl = extra.optString("get").trim().takeIf { it.startsWith("https://") },
        )
    }

    fun installed(context: Context, app: App): Boolean =
        app.packages.any { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }

    /** The app [record] manages when it is not installed, else null. Asks Android: off the main thread. */
    fun missing(context: Context, record: PluginRecord): App? = of(record)?.takeIf { !installed(context, it) }

    /** The panel's lead notice for a missing app: "<app> isn't installed", with Get when the plugin names where. */
    fun noticeGroup(context: Context, record: PluginRecord, surface: String): CatalogGroup? {
        val app = missing(context, record) ?: return null
        return CatalogGroup(
            id = "plugin_${record.manifest.id}_${surface}_app_missing",
            title = null,
            items = listOf(
                NoticeItem(
                    id = "plugin_${record.manifest.id}_${surface}_app_missing_notice",
                    notice = Notice(
                        tone = NoticeTone.Caution,
                        title = "${app.shownName} isn't installed",
                        body = "${record.manifest.label} works with it once it is on this device.",
                        action = app.getUrl?.let { url ->
                            NoticeAction("Get") { ctx ->
                                dev.droidtop.library.Links.open(ctx, url)
                            }
                        },
                        source = record.manifest.label,
                    ),
                ),
            ),
        )
    }

    /** The reason a row that needs the app shows while it is missing. */
    fun reason(app: App): String = "Needs ${app.shownName}, which isn't installed"
}

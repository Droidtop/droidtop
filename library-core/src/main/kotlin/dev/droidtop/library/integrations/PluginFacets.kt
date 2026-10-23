package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.FacetGuards
import dev.droidtop.pluginhost.FacetSubject
import dev.droidtop.pluginhost.FacetViews
import dev.droidtop.pluginhost.Facets
import dev.droidtop.pluginhost.NetScope
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginView
import org.json.JSONObject

/**
 * Asking a plugin for its facets on a target (docs/plugin-api.md 1.10): `ui.facets` `get {target, subject?, context}`,
 * read, put through [FacetGuards.admit] with what droidtop knows of the plugin, and drawn by the one renderer
 * ([PluginViews], through [FacetViews]). Every target that draws facets asks through here.
 */
object PluginFacets {
    const val POINT = FacetGuards.POINT
    const val OP_GET = "get"

    const val QUICK_MENU = "quickmenu"
    const val GAME_PAGE = "game.page"
    const val PLUGIN_PAGE = "plugin.page"
    const val SETTINGS_PAGE = "settings.page"
    const val ITEM_PAGE = "item.page"

    /** True when [record] declares [target] on a `ui.facets` entry. Reads the manifest only. */
    fun declares(record: PluginRecord, target: String): Boolean =
        target in FacetGuards.declaredTargets(record.manifest.v2.provides)

    /** What the guards know of [record]: its declared targets, its sites, whether it may give store information. */
    fun guardsFor(record: PluginRecord): FacetGuards.Plugin {
        val v2 = record.manifest.v2
        return FacetGuards.Plugin(
            id = record.manifest.id,
            declaredTargets = FacetGuards.declaredTargets(v2.provides),
            domains = NetScope.declaredDomains(v2.permissions),
            storeOrSource = v2.provides.any { it.point == "library.sources" || it.point == "library.updates" },
            // droidtop's own stores are built in; no store plugin point exists yet, so `owned` is taken from none.
            signedInStore = false,
            permissions = v2.permissions.map { it.id }.toSet(),
        )
    }

    /** The args of a `get` for [target], about [subject] when the target has one. */
    fun getArgs(target: String, subject: JSONObject? = null): JSONObject =
        JSONObject().put("target", target).also { a -> subject?.let { a.put("subject", it) } }

    /**
     * A `get` reply as the page droidtop draws: only what [args]' target takes from [record], in its order. Null when
     * the reply is not facets, so the caller reads it as a view.
     */
    fun view(record: PluginRecord, args: JSONObject, data: JSONObject, messages: MutableList<String>): PluginView? {
        if (data.optJSONArray("facets") == null) return null
        val target = args.optString("target")
        val subject = Facets.subjectOf(args.optJSONObject("subject")) ?: FacetSubject.Plugin
        val reading = Facets.read(data, subject)
        messages += reading.messages
        val admitted = FacetGuards.admit(reading.facets, target, guardsFor(record), messages)
        return FacetViews.toView(admitted, data.optString("title").takeIf { it.isNotBlank() })
    }
}

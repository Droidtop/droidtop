package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.FacetGuards
import dev.droidtop.pluginhost.FacetSubject
import dev.droidtop.pluginhost.FacetViews
import dev.droidtop.pluginhost.Facets
import dev.droidtop.pluginhost.NetScope
import dev.droidtop.pluginhost.PluginGrants
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

    /**
     * True when [record] declares [target] and the person has not switched that target off under its "Where it appears".
     * A target switched off is not asked for (docs/plugin-api.md 1.10, "The person arranges them"). Reads files only.
     */
    fun shows(context: Context, record: PluginRecord, target: String): Boolean =
        declares(record, target) && FacetGuards.targetOn(PluginGrants.forContext(context).read(record.manifest.id), target)

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

    /** The plugin's own page (`plugin.page`): its facets, drawn by the one renderer, about the plugin itself. */
    fun pageScreen(record: PluginRecord): dev.droidtop.library.settings.CatalogScreen = PluginViews.screen(
        record = record,
        point = POINT,
        op = OP_GET,
        id = "plugin_facets_page_${record.manifest.id}",
        title = record.manifest.label,
        args = getArgs(PLUGIN_PAGE),
        hostContext = JSONObject().put("target", "plugin"),
    )

    /** The subject of a game page's `get`: the game by droidtop's id, when [targetArg] carries its identity. */
    fun gameSubject(targetArg: JSONObject): JSONObject? =
        targetArg.optString("id").takeIf { it.isNotBlank() }?.let { JSONObject().put("type", "game").put("gameId", it) }

    /** The args of a `get` for [target], about [subject] when the target has one. */
    fun getArgs(target: String, subject: JSONObject? = null): JSONObject =
        JSONObject().put("target", target).also { a -> subject?.let { a.put("subject", it) } }

    /**
     * A `get` reply as the page droidtop draws: only what [args]' target takes from [record], in its order. Null when
     * the reply is not facets, so the caller reads it as a view.
     */
    fun view(record: PluginRecord, args: JSONObject, data: JSONObject, messages: MutableList<String>, context: Context? = null): PluginView? {
        if (data.optJSONArray("facets") == null) return null
        val target = args.optString("target")
        // A game page's facets are about its game even when the plugin may not learn which one it is (library.read).
        val subject = Facets.subjectOf(args.optJSONObject("subject")) ?: if (target == GAME_PAGE) FacetSubject.Game("") else FacetSubject.Plugin
        val reading = Facets.read(data, subject)
        messages += reading.messages
        val admitted = FacetGuards.admit(reading.facets, target, guardsFor(record), messages)
        // What it gave is remembered for "Where it appears", hidden ones too, so they can be shown again there.
        val shown = if (context == null) {
            admitted
        } else {
            Seen.remember(context, record.manifest.id, target, admitted)
            FacetGuards.unhidden(admitted, target, PluginGrants.forContext(context).read(record.manifest.id))
        }
        return FacetViews.toView(shown, data.optString("title").takeIf { it.isNotBlank() })
    }

    /**
     * The facets droidtop last drew from each plugin, per target, by id and the words it showed: what "Where it
     * appears" lists with a switch each (docs/plugin-api.md 1.10, "The person arranges them"). One small file per plugin.
     */
    object Seen {
        private const val MAX_PER_TARGET = 40
        private val SAFE = Regex("[A-Za-z0-9._-]{1,120}")

        private fun file(context: Context, pluginId: String): java.io.File? =
            pluginId.takeIf { SAFE.matches(it) && !it.startsWith(".") }?.let { java.io.File(java.io.File(context.filesDir, "plugin-facets-seen"), "$it.json") }

        /** The words a facet shows, for its switch: its node's title, a group's title, or its id. */
        fun labelOf(facet: dev.droidtop.pluginhost.Facet): String =
            FacetViews.node(facet)?.title ?: (facet.body as? dev.droidtop.pluginhost.FacetBody.Group)?.title ?: facet.id

        fun remember(context: Context, pluginId: String, target: String, facets: List<dev.droidtop.pluginhost.Facet>) {
            val file = file(context, pluginId) ?: return
            runCatching {
                val root = if (file.isFile) JSONObject(file.readText()) else JSONObject()
                val list = org.json.JSONArray()
                facets.take(MAX_PER_TARGET).forEach { list.put(JSONObject().put("id", it.id).put("label", labelOf(it))) }
                if (root.optJSONArray(target)?.toString() == list.toString()) return
                root.put(target, list)
                file.parentFile?.mkdirs()
                val part = java.io.File(file.parentFile, "${file.name}.part")
                part.writeText(root.toString())
                if (!part.renameTo(file)) part.delete()
            }
        }

        /** Per target, the facets last drawn there as (id, label), in drawing order. */
        fun read(context: Context, pluginId: String): Map<String, List<Pair<String, String>>> {
            val file = file(context, pluginId)?.takeIf { it.isFile } ?: return emptyMap()
            val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
            return root.keys().asSequence().associateWith { target ->
                val list = root.optJSONArray(target) ?: org.json.JSONArray()
                (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { it.optString("id") to it.optString("label") } }
                    .filter { it.first.isNotBlank() }
            }
        }
    }
}

package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.Facet
import dev.droidtop.pluginhost.FacetBody
import dev.droidtop.pluginhost.ViewNode
import org.json.JSONObject

/**
 * A source's own search options (docs/plugin-api.md 1.10, `search.filter`): what the person set on its
 * `search.filter` facets, kept per source plugin as the System pick is, and sent with every search as `args.filters`
 * (`{facetId: value}`, typed). droidtop holds the values; the plugin only says which options there are.
 */
object SourceFilters {
    private const val PREFS = "source_search_filters"

    /** What the person set for [pluginId]'s options; empty when nothing was set. */
    fun read(context: Context, pluginId: String): JSONObject =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pluginId, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** Keeps one option's new value, typed as [node] holds it. */
    fun put(context: Context, pluginId: String, node: ViewNode, raw: String) {
        val value = typed(node, raw) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(pluginId, read(context, pluginId).put(node.id, value).toString()).apply()
    }

    /** An input's raw value as the plugin reads it; null for a node that is no option. Pure. */
    fun typed(node: ViewNode, raw: String): Any? = when (node) {
        is ViewNode.Toggle -> raw == "true"
        is ViewNode.Slider -> raw.toDoubleOrNull() ?: return null
        is ViewNode.Choice, is ViewNode.Text -> raw
        else -> null
    }

    /**
     * [facets] with what the person set laid over the plugin's own defaults, so the page shows their choices. A kept
     * choice that is no longer one of the options is left out. Pure.
     */
    fun applied(facets: List<Facet>, kept: JSONObject): List<Facet> = facets.map { facet ->
        val body = facet.body
        if (body is FacetBody.Group) return@map facet.copy(body = body.copy(facets = applied(body.facets, kept)))
        if (!kept.has(facet.id)) return@map facet
        val value = kept.opt(facet.id)
        facet.copy(
            body = when (body) {
                is FacetBody.Toggle -> (value as? Boolean)?.let { body.copy(value = it) } ?: body
                is FacetBody.Choice -> (value as? String)?.takeIf { v -> body.options.any { it.first == v } }?.let { body.copy(value = it) } ?: body
                is FacetBody.Slider -> (value as? Number)?.toDouble()?.coerceIn(body.min, body.max)?.let { body.copy(value = it) } ?: body
                is FacetBody.Input -> (value as? String)?.let { body.copy(value = it) } ?: body
                else -> body
            },
        )
    }
}

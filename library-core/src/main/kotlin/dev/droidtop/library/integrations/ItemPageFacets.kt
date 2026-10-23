package dev.droidtop.library.integrations

import dev.droidtop.library.UpdateSources
import dev.droidtop.pluginhost.Facet
import dev.droidtop.pluginhost.FacetBody
import dev.droidtop.pluginhost.FacetGuards
import dev.droidtop.pluginhost.FacetSubject
import dev.droidtop.pluginhost.Facets
import org.json.JSONObject

/**
 * An item's record page from its `item.page` bundles (docs/plugin-api.md 1.10, contract 3): the `metadata`,
 * `description`, `image`, `download` and `links` a source gives about one of its items, through the guards, as the
 * record droidtop already draws (docs/SPEC.md 7g, "Getting an update"). A contract 2 `record` is read as before. Pure.
 */
object ItemPageFacets {
    /**
     * The item [id]'s part of a reply: the entry of `items: [{id, facets}]` for it, or the reply itself when it carries
     * `facets` of its own. Null when the reply has no facets for it.
     */
    fun itemJson(data: JSONObject, id: String): JSONObject? {
        val items = data.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("id") == id && item.optJSONArray("facets") != null) return item
            }
            return null
        }
        return data.takeIf { it.optJSONArray("facets") != null }
    }

    /**
     * The facets of [item] that the item page draws from [plugin]: read about the item, a bundle without targets taken
     * as the item page's (an item's facets are kept for its page), and admitted as for any target.
     */
    fun admitted(item: JSONObject, source: String, id: String, plugin: FacetGuards.Plugin, messages: MutableList<String>): List<Facet> {
        val reading = Facets.read(item, FacetSubject.Item(source, id))
        messages += reading.messages
        val facets = reading.facets.map { if (it.targets.isEmpty()) it.copy(targets = listOf(PluginFacets.ITEM_PAGE)) else it }
        return FacetGuards.admit(facets, PluginFacets.ITEM_PAGE, plugin, messages)
    }

    /** The record [facets] tell of item [externalId]; null when they hold none of the record's bundles. */
    fun record(facets: List<Facet>, externalId: String): UpdateSources.Record? {
        val bodies = facets.map { it.body }
        val meta = bodies.filterIsInstance<FacetBody.Metadata>().firstOrNull()
        val about = bodies.filterIsInstance<FacetBody.Description>().firstOrNull()
        val images = bodies.filterIsInstance<FacetBody.Image>().map { it.media }
        val downloads = bodies.filterIsInstance<FacetBody.Download>().flatMap { it.entries }
        val links = bodies.filterIsInstance<FacetBody.Links>().flatMap { it.entries }
        if (meta == null && about == null && images.isEmpty() && downloads.isEmpty() && links.isEmpty()) return null
        return UpdateSources.Record(
            externalId = externalId,
            title = meta?.title ?: "#$externalId",
            url = (links.firstOrNull { it.linkKind == "source" } ?: links.firstOrNull())?.url,
            version = meta?.version,
            developer = meta?.developer,
            status = meta?.status,
            rating = meta?.rating?.takeIf { it in 0.0..1.0 },
            votes = meta?.votes?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
            tags = meta?.tags.orEmpty(),
            updatedEpochMs = meta?.updated?.takeIf { it > 0 },
            cover = (images.firstOrNull { it.role == "cover" } ?: images.firstOrNull())?.url,
            overview = about?.overview,
            changelog = about?.changelog,
            downloads = downloads.mapNotNull { d ->
                val url = d.url?.takeIf { it.startsWith("https://") }
                if (url == null && d.link.isNullOrBlank()) return@mapNotNull null
                UpdateSources.Download(section = d.section, host = d.host, label = d.label, link = d.link.orEmpty(), url = url)
            },
        )
    }
}

package dev.droidtop.library.integrations

import dev.droidtop.library.RecordSections
import dev.droidtop.library.UpdateSources
import dev.droidtop.pluginhost.Facet
import dev.droidtop.pluginhost.FacetBody
import dev.droidtop.pluginhost.FacetGuards
import dev.droidtop.pluginhost.FacetSubject
import dev.droidtop.pluginhost.FacetViews
import dev.droidtop.pluginhost.NetScope
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
            platforms = meta?.platforms.orEmpty(),
            languages = meta?.languages.orEmpty(),
            contentNotes = meta?.contentNotes.orEmpty(),
            updatedEpochMs = meta?.updated?.takeIf { it > 0 },
            cover = (images.firstOrNull { it.role == "cover" } ?: images.firstOrNull())?.url,
            overview = about?.overview,
            // A changelog the source gives only as a named section is still the changelog (Droidtop/tracker#845).
            changelog = about?.changelog ?: about?.sections?.firstOrNull { RecordSections.isChangelog(it.title) }?.body,
            sections = about?.sections.orEmpty()
                .filterNot { RecordSections.isChangelog(it.title) && about?.changelog == null }
                .map { UpdateSources.Section(it.title, it.body) },
            thread = about?.thread,
            downloads = distinctDownloads(downloads.mapNotNull { d ->
                val url = d.url?.takeIf { it.startsWith("https://") }
                val parts = d.parts.mapNotNull { p ->
                    val partUrl = p.url?.takeIf { it.startsWith("https://") }
                    if (partUrl == null && p.link.isNullOrBlank()) null else UpdateSources.Download.Part(p.label, partUrl, p.link, p.size)
                }
                if (url == null && d.link.isNullOrBlank() && parts.isEmpty()) return@mapNotNull null
                UpdateSources.Download(section = d.section, host = d.host, label = d.label, link = d.link.orEmpty(), url = url, systems = d.systems, parts = parts, role = d.role, official = d.official, build = d.build)
            }),
            controls = controls(facets),
        )
    }

    /**
     * [downloads] with each mirror once (Droidtop/tracker#838; the owner: "links are duplicated. Links that are marked
     * 'windows/linux' are presented twice under that title"). A download is one link however many systems it covers:
     * entries with the same address, or with no address and the same section, host and token, are one, holding every
     * system any of them named. The first keeps its place. Pure.
     */
    fun distinctDownloads(downloads: List<UpdateSources.Download>): List<UpdateSources.Download> {
        val merged = LinkedHashMap<String, UpdateSources.Download>()
        for (d in downloads) {
            val key = when {
                d.url != null -> "url:${d.url}"
                d.link.isNotBlank() -> "link:${d.section}\u0000${d.host.lowercase()}\u0000${d.link}"
                else -> "parts:" + d.parts.joinToString("\u0000") { it.url ?: it.link.orEmpty() }
            }
            val seen = merged[key]
            merged[key] = if (seen == null) d else seen.copy(systems = (seen.systems + d.systems).distinct())
        }
        return merged.values.toList()
    }

    /**
     * The source's own controls among [facets], in its order: a toggle (watch this thread), an action, a link and a
     * notice, with what pressing each sends made by the one renderer ([FacetViews]), so it travels as on any target.
     */
    fun controls(facets: List<Facet>): List<UpdateSources.Control> = facets.mapNotNull { f ->
        when (val b = f.body) {
            is FacetBody.Toggle -> b.call?.let { UpdateSources.Control(f.id, UpdateSources.Control.Kind.TOGGLE, b.label, b.sub, on = b.value, action = FacetViews.action(it, f.id, "bool")) }
            is FacetBody.Action -> UpdateSources.Control(f.id, UpdateSources.Control.Kind.ACTION, b.label, b.sub, action = FacetViews.action(b.call, f.id, null), confirm = b.confirm != "none")
            is FacetBody.Link -> UpdateSources.Control(f.id, UpdateSources.Control.Kind.LINK, b.label, NetScope.hostOf(b.url), url = b.url)
            is FacetBody.Notice -> UpdateSources.Control(f.id, UpdateSources.Control.Kind.NOTICE, b.title, b.sub, action = b.call?.let { FacetViews.action(it, f.id, null) })
            else -> null
        }
    }
}

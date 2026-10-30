package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.RECORD_PREFS
import dev.droidtop.library.RecordMirrors
import dev.droidtop.library.RecordAction
import dev.droidtop.library.RecordRow
import dev.droidtop.library.SYSTEM_KEY_PREFIX
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.downloadRows
import dev.droidtop.library.downloadSystems
import dev.droidtop.library.ratingLine
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.DownloadSystems
import dev.droidtop.pluginhost.Facet
import dev.droidtop.pluginhost.FacetBody
import dev.droidtop.pluginhost.FacetViews
import dev.droidtop.pluginhost.NetScope
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.pluginhost.PluginView
import dev.droidtop.pluginhost.PluginViewCall
import dev.droidtop.pluginhost.PluginWebAccount
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/**
 * A source result's page drawn from the item's bundles (docs/SPEC.md 12a, "A source's item page"; Droidtop/tracker#737):
 * a `library.sources` `detail` reply of facets, through the item page's guards, as the record droidtop draws for an
 * update (docs/SPEC.md 7g): an Overview, the Downloads (a release in parts is one row, fetched as one set), the
 * Changelog and the Links. What is not a bundle (a switch, a button, a notice) stays the plugin's page, drawn by the one
 * renderer ([FacetViews]). Picking a download places it in the page's destination, as the source's `acquire` does.
 */
object SourceItemPage {
    /** The page's own groups, and the rest of the reply's facets as a view. */
    class Page(val groups: List<CatalogGroup>, val rest: PluginView)

    /**
     * [data], [record]'s `detail` reply for its result [itemId], as an item page; null when it carries no facets about the
     * item or none of the bundles, so the reply is read as any view. [ref] and [hostContext] are the page's `acquire`'s.
     */
    fun read(context: Context, record: PluginRecord, itemId: String, ref: JSONObject, hostContext: JSONObject, data: JSONObject, messages: MutableList<String>): Page? {
        val item = ItemPageFacets.itemJson(data, itemId) ?: return null
        // A detail reply of facets is the source's declaration of the item page, as a `record` reply is for an update.
        val guards = PluginFacets.guardsFor(record).let { it.copy(declaredTargets = it.declaredTargets + PluginFacets.ITEM_PAGE) }
        val facets = ItemPageFacets.admitted(item, record.manifest.id, itemId, guards, messages)
        val found = ItemPageFacets.record(facets, itemId) ?: return null
        val links = links(facets)
        val rest = facets.filterNot { isDrawnHere(it.body) }
        val picked = context.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE).getString(SYSTEM_KEY_PREFIX + record.manifest.id, null)
        // The record asked again, for a link its page says has expired (docs/plugin-api.md 1.11, "One link path: the
        // record"): the same `detail` call, read through the same guards.
        val again: suspend (Context) -> UpdateSources.Record? = { ctx ->
            val reply = PluginViews.call(ctx, record, "library.sources", "detail", PluginViewCall.args(JSONObject().put("ref", ref), emptyMap(), hostContext))
            reply.takeIf { it.ok }?.let { ItemPageFacets.itemJson(it.data, itemId) }
                ?.let { ItemPageFacets.record(ItemPageFacets.admitted(it, record.manifest.id, itemId, guards, mutableListOf()), itemId) }
        }
        val get = Getter(record, ref, hostContext, found, again)
        val groups = listOfNotNull(
            CatalogGroup(id = "item_overview", title = "Overview", items = overview(found)),
            CatalogGroup(id = "item_downloads", title = "Downloads", items = downloads(record, found, picked, get)),
            found.changelog?.let { CatalogGroup(id = "item_changelog", title = "Changelog", items = listOf(TextBlockItem(id = "item_changelog_text", text = it))) },
            links.takeIf { it.isNotEmpty() }?.let { list ->
                CatalogGroup(id = "item_links", title = "Links", items = list.mapIndexed { index, link -> linkItem("item_link_$index", link, get) })
            },
        )
        return Page(groups, FacetViews.toView(rest, data.optString("title").takeIf { it.isNotBlank() }))
    }

    /** Whether [body] is drawn by this page (the record's bundles and the links), not by the renderer. Pure. */
    fun isDrawnHere(body: FacetBody): Boolean = body is FacetBody.Metadata || body is FacetBody.Description ||
        body is FacetBody.Image || body is FacetBody.Download || body is FacetBody.Links || body is FacetBody.Link

    /** The item's links: its `links` bundle's, then each `link`, in the source's order; one address once. Pure. */
    fun links(facets: List<Facet>): List<FacetBody.Link> = facets.flatMap { f ->
        when (val b = f.body) {
            is FacetBody.Links -> b.entries
            is FacetBody.Link -> listOf(b)
            else -> emptyList()
        }
    }.distinctBy { it.url }

    /** The Overview: what the source says of the item, then its description. Pure. */
    fun overview(record: UpdateSources.Record, formatDate: (Long) -> String = { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }): List<CatalogItem> {
        val facts = listOfNotNull(
            record.version?.let { RecordRow("Version", it) },
            record.developer?.let { RecordRow("Developer", it) },
            record.status?.let { RecordRow("Status", it) },
            ratingLine(record.rating, record.votes)?.let { RecordRow("Rating", it) },
            record.updatedEpochMs?.let { RecordRow("Updated", formatDate(it)) },
            record.tags.takeIf { it.isNotEmpty() }?.let { RecordRow("Tags", subtitle = it.joinToString(", ")) },
        )
        return facts.mapIndexed { index, row -> ActionItem(id = "item_fact_$index", title = row.title, subtitle = row.subtitle, value = row.value) } +
            TextBlockItem(id = "item_about", text = record.overview ?: "The source gives no description")
    }

    /** The Downloads: the record page's rows, the system its choice above them, each link asked once more and then got. */
    private fun downloads(record: PluginRecord, found: UpdateSources.Record, picked: String?, get: Getter): List<CatalogItem> {
        val offered = downloadSystems(found)
        val system = if (offered.size >= 2) {
            ChoiceItem(
                id = "item_system",
                title = "System",
                subtitle = "Which system's links are listed",
                options = offered.map { ChoiceOption(it, DownloadSystems.LABELS[it] ?: it) },
                current = picked?.takeIf { it in offered } ?: DownloadSystems.first(offered),
                onSelect = { ctx, value ->
                    ctx.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE).edit().putString(SYSTEM_KEY_PREFIX + record.manifest.id, value).apply()
                },
            )
        } else {
            null
        }
        val rows = downloadRows(found, picked).filterNot { it.action == RecordAction.PickSystem }.mapIndexed { index, row ->
            val id = "item_download_$index"
            when (val action = row.action) {
                is RecordAction.Download -> AsyncActionItem(
                    id = id,
                    title = row.title,
                    subtitle = row.subtitle,
                    value = row.value,
                    confirmTitle = "Download this?",
                    run = { ctx, onStatus -> get.download(ctx, action.download, onStatus) },
                )
                else -> ActionItem(id = id, title = row.title, subtitle = row.subtitle, value = row.value)
            }
        }
        return listOfNotNull(system) + rows
    }

    /** A link: opened in droidtop's web view with the source's session, where a download the person starts is taken over. */
    private fun linkItem(id: String, link: FacetBody.Link, get: Getter): CatalogItem = AsyncActionItem(
        id = id,
        title = link.label,
        value = NetScope.hostOf(link.url),
        run = { ctx, onStatus -> get.open(ctx, link.url, onStatus) },
    )

    /** What picking a download or a link does: the item's source fetches it into the page's destination. */
    private class Getter(
        val record: PluginRecord,
        val ref: JSONObject,
        val hostContext: JSONObject,
        val item: UpdateSources.Record,
        val again: suspend (Context) -> UpdateSources.Record?,
    ) {
        private val title get() = "Download ${item.title}"

        suspend fun download(context: Context, d: UpdateSources.Download, onStatus: (String) -> Unit): String {
            // A release in parts: every part, then one job that joins and unpacks them (docs/plugin-api.md 1.10, `parts`).
            if (d.parts.isNotEmpty()) {
                val partsAgain = suspend { current(context, d)?.parts }
                val gathered = UpdateLinks.gatherParts(context, record, d.parts, onStatus, partsAgain) { link, which ->
                    onStatus("$which: asking ${record.manifest.label}")
                    val result = PluginViews.runJob(context, record, "library.sources", "acquire", acquireArgs(link), "$title ($which)", onStatus)
                    if (result.ok) dev.droidtop.pluginhost.AcquireDownloads.parse(result.values).orEmpty() else emptyList()
                }.getOrElse { return it.message ?: "A part was not downloaded, so nothing was added" }
                return said(AcquireDownload.run(context, record, title, hostContext, emptyMap(), gathered, onStatus))
            }
            d.url?.let { return open(context, it, onStatus, picked = d) }
            // A token only the source understands: its `acquire`, told which link was picked.
            return said(PluginViews.acquire(context, record, title, hostContext, acquireArgs(d.link), onStatus))
        }

        /**
         * Opens [url] out of sight first, then in droidtop's web view if the person has something to do there (#599, #783);
         * whatever starts is placed. A download's link ([picked]) whose page says it has expired is asked of the source
         * again, from the record, once. Blocks: never on the main thread.
         */
        suspend fun open(context: Context, url: String, onStatus: (String) -> Unit, picked: UpdateSources.Download? = null): String {
            if (!NetScope.isHttps(url)) return "${record.manifest.label} gave a link droidtop does not open"
            onStatus("Opening the link from ${record.manifest.label}")
            val refresh = picked?.let { d ->
                {
                    onStatus("That link had expired. Asking ${record.manifest.label} for a current one")
                    kotlinx.coroutines.runBlocking { current(context, d) }?.url?.takeIf { it != url }
                }
            }
            val captured = PluginWebAccount.openForDownloads(context, record, url, DownloadJobs.MAX_FILES, refresh)
            if (captured.isEmpty()) return "Nothing was downloaded from that page"
            return said(AcquireDownload.run(context, record, title, hostContext, emptyMap(), UpdateLinks.descriptorsFor(captured), onStatus))
        }

        /** [picked], one of this page's downloads, in the record asked again ([RecordMirrors.sameIn]); null when it is gone. */
        private suspend fun current(context: Context, picked: UpdateSources.Download): UpdateSources.Download? =
            again(context)?.let { fresh -> RecordMirrors.sameIn(picked, item.downloads, fresh.downloads) }

        /** `acquire {ref, values: {link, version?}, context}`: the picked link, as a source's detail page's job sends it. */
        private fun acquireArgs(link: String): JSONObject {
            val values = JSONObject().put("link", link)
            item.version?.let { values.put("version", it) }
            return JSONObject().put("ref", ref).put("values", values).put("context", hostContext)
        }

        private fun said(result: PluginResult): String =
            if (result.ok) result.values["summary"] ?: result.values["message"] ?: "Done" else "${record.manifest.label}: ${result.error ?: "the download failed"}"
    }
}

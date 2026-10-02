package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.pluginhost.PluginView
import dev.droidtop.pluginhost.ViewNode
import dev.droidtop.pluginhost.ViewSection
import java.io.File
import org.json.JSONObject

/** Shared source search screens: contract 2 views and the host's contract 1 form use the same result renderer. */
object SourceScreens {
    fun searchScreen(source: AcquireContentSource.Plugin, systemId: String, systemName: String, systemFolder: File): CatalogScreen {
        val adapter = PluginGameSource(source)
        if (source.record.manifest.contractVersion < 2) return legacySearchScreen(source, adapter, systemId, systemName, systemFolder)
        val form = PluginView(null, null, listOf(ViewSection("search", null, listOf(ViewNode.Text("query", "Search", null, "", null)))))
        var lastValues: Map<String, String>? = null
        var cachedGroups: List<CatalogGroup> = emptyList()
        val screen = PluginViews.screen(
            source.record, "library.sources", "form", "source_search_${source.id}_$systemId", source.label,
            hostContext = JSONObject().put("system", JSONObject().put("id", systemId).put("name", systemName)).put("destination", systemFolder.absolutePath),
            fallback = { form },
            extraGroups = { context, values ->
                if (lastValues != values) {
                    cachedGroups = resultGroups(context, adapter, systemId, systemName, systemFolder, values["query"].orEmpty(), values)
                    lastValues = values.toMap()
                }
                cachedGroups
            },
            extraWhenFailed = { listOfNotNull(adapter.settingsScreen()?.let { NestedScreenItem("source_settings_${source.id}", "Open ${source.label} settings", inline = it) }) },
        )
        return screen
    }

    private fun legacySearchScreen(source: AcquireContentSource.Plugin, adapter: PluginGameSource, systemId: String, systemName: String, folder: File): CatalogScreen {
        var query = ""
        var groups: List<CatalogGroup> = emptyList()
        return CatalogScreen("source_search_${source.id}_$systemId", source.label, "Search ${source.label} for $systemName", groups = {
            listOf(CatalogGroup("source_form_${source.id}", null, listOf(TextInputItem(
                "source_query_${source.id}", "Search", value = query,
                onChange = { context, committed ->
                    query = committed
                    groups = resultGroups(context, adapter, systemId, systemName, folder, committed, mapOf("query" to committed))
                },
            )))) + groups
        })
    }

    private suspend fun resultGroups(context: Context, adapter: PluginGameSource, systemId: String, systemName: String, folder: File, query: String, values: Map<String, String>): List<CatalogGroup> {
        if (query.isBlank() && values.values.all(String::isBlank)) return emptyList()
        val source = adapter.source
        val results = adapter.searchWithValues(context, query.trim(), systemId, values, folder, systemName)
        val settings = adapter.settingsScreen()
        return results.fold(
            onSuccess = { rows -> listOf(CatalogGroup("source_results_${source.id}", "Results (${rows.size})", rows.map { result ->
                NestedScreenItem("source_result_${source.id}_${result.id}", result.title, subtitle = result.columns.joinToString(" · ").ifBlank { result.subtitle }, valueLabel = result.badges.joinToString(" · ").takeIf { it.isNotBlank() }?.let { value -> { _: Context -> value } }, inline = adapter.detailScreen(result, systemId, systemName, folder))
            })) },
            onFailure = { error -> listOf(CatalogGroup("source_error_${source.id}", null, buildList {
                // A plain sentence first; what the source actually reported (a platform exception text, a
                // stack line) is for its developer and sits one step behind "Technical details".
                add(ActionItem("source_failure_${source.id}", "Couldn't search ${source.label}", subtitle = "It did not answer. Try again in a moment.", run = {}))
                val technical = error.message?.trim().orEmpty().ifBlank { error::class.java.simpleName }
                add(
                    NestedScreenItem(
                        "source_failure_detail_${source.id}", "Technical details", subtitle = "What the source reported, for its developer",
                        inline = CatalogScreen("source_failure_detail_screen_${source.id}", "Technical details", groups = {
                            listOf(CatalogGroup("detail", null, listOf(ActionItem("source_failure_text_${source.id}", technical, run = {}))))
                        }),
                    ),
                )
                if (settings != null) add(NestedScreenItem("source_settings_${source.id}", "Open ${source.label} settings", inline = settings))
            })) },
        )
    }

    fun noDestination(result: AcquireContentResult) = CatalogScreen("source_no_destination_${result.id}", result.title, groups = { listOf(CatalogGroup("info", null, listOf(ActionItem("destination_missing", "Open this game's own system to download it", run = {})))) })

    fun legacyDetail(source: AcquireContentSource.Plugin, result: AcquireContentResult, systemId: String?, systemName: String?, destination: File?) =
        if (destination == null) noDestination(result) else {
            var picked = result.options.firstOrNull()?.index ?: 0
            CatalogScreen("source_detail_${source.id}_${result.id}", result.title, groups = { context ->
                listOf(CatalogGroup("detail", null, buildList {
                    add(ActionItem("facts", listOfNotNull(result.platform, result.sizeLabel).joinToString(" · ").ifBlank { result.title }, run = {}))
                    if (result.options.size > 1) add(dev.droidtop.library.settings.ChoiceItem(id = "option", title = "Download option", options = result.options.map { dev.droidtop.library.settings.ChoiceOption(it.index.toString(), it.label) }, current = picked.toString(), onSelect = { _, value -> picked = value.toIntOrNull() ?: picked }))
                    add(AsyncActionItem("download", "Download", run = { ctx, status -> AcquireContentSources.runDownloadAndAwait(ctx, source, destination, result, status, picked) }))
                }))
            })
        }
}

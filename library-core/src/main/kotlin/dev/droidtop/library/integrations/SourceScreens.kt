package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.pluginhost.PluginView
import dev.droidtop.pluginhost.SourceSystems
import dev.droidtop.pluginhost.ViewNode
import dev.droidtop.pluginhost.ViewSection
import java.io.File
import org.json.JSONObject

/** Shared source search screens: a source's search form (its own view, or the host's fallback form) and the result list under it. */
object SourceScreens {
    /**
     * What a source's page needs to know about the device, read once for a whole Game sources list (off the main
     * thread) and not once per row: the console systems, the folders that exist, and where a PC game goes ([pcFolder],
     * null while the person has named no game folder).
     */
    class EntryContext(val systems: List<ConsoleSystemDef>, val folders: List<Pair<File, ConsoleSystemDef>>, val pcFolder: File?, val root: File)

    /** Reads the [EntryContext]. Call off the main thread. */
    suspend fun entryContext(context: Context): EntryContext {
        val systems = ConsoleSystemsRepository.allSystems(context)
        val folders = SystemFolders.all(context, systems.associateBy { it.id })
        val pc = SourceDestinations.pick(null, null, null, systems, folders, GamesRoots.configured(context))
        return EntryContext(systems, folders, pc.folder, GamesRoots.current(context).first())
    }

    /**
     * Where a source opens from Game sources (Droidtop/tracker#540): its search, with no library system needed. A
     * source that serves only PC systems opens straight on the PC search; any other asks which system, the PC
     * (when it serves it) first and then the systems it serves ([downloadTargets], so a system with no folder yet is
     * offered too and its folder is made when a download is placed). A source that names no system opens the same page.
     */
    fun entryScreen(source: AcquireContentSource.Plugin, entry: EntryContext): CatalogScreen {
        val serves = source.systems
        if (serves.servesOnlyPc()) return searchScreen(source, SourceSystems.PC, "PC", entry.pcFolder)
        fun item(folder: File, system: ConsoleSystemDef) = NestedScreenItem(
            id = "source_pick_${source.id}_${system.id}",
            title = system.displayName,
            inline = searchScreen(source, system.id, system.displayName, folder),
        )
        val (yours, others) = AcquireContentSources.downloadTargets(
            existing = entry.folders.filter { (_, s) -> s.id !in SourceSystems.PC_FAMILY && source.serves(s.id) },
            all = entry.systems.filter { it.id !in SourceSystems.PC_FAMILY && source.serves(it.id) },
            root = entry.root,
        )
        val groups = listOfNotNull(
            CatalogGroup("source_entry_pc_${source.id}", null, listOf(
                NestedScreenItem("source_pick_${source.id}_pc", "PC games", inline = searchScreen(source, SourceSystems.PC, "PC", entry.pcFolder)),
            )).takeIf { serves.serves(SourceSystems.PC) },
            yours.takeIf { it.isNotEmpty() }?.let { list -> CatalogGroup("source_entry_yours_${source.id}", "Your systems", list.map { (f, s) -> item(f, s) }) },
            others.takeIf { it.isNotEmpty() }?.let { list -> CatalogGroup("source_entry_other_${source.id}", if (yours.isEmpty()) null else "Other systems", list.map { (f, s) -> item(f, s) }) },
        )
        return CatalogScreen(
            id = "source_entry_${source.id}",
            title = source.label,
            subtitle = source.description,
            indexGroups = { emptyList() },
            groups = {
                groups.ifEmpty {
                    listOf(CatalogGroup("source_entry_none_${source.id}", null, listOf(
                        ActionItem("source_entry_none", "No system to search for", subtitle = "${source.label} lists no system droidtop knows."),
                    )))
                }
            },
        )
    }

    fun searchScreen(source: AcquireContentSource.Plugin, systemId: String, systemName: String, systemFolder: File?): CatalogScreen {
        val adapter = PluginGameSource(source)
        val form = PluginView(null, null, listOf(ViewSection("search", null, listOf(ViewNode.Text("query", "Search", null, "", null)))))
        var lastValues: Map<String, String>? = null
        var cachedGroups: List<CatalogGroup> = emptyList()
        val screen = PluginViews.screen(
            source.record, "library.sources", "form", "source_search_${source.id}_$systemId", source.label,
            hostContext = JSONObject().put("system", JSONObject().put("id", systemId).put("name", systemName)).also { host -> systemFolder?.let { host.put("destination", it.absolutePath) } },
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

    private suspend fun resultGroups(context: Context, adapter: PluginGameSource, systemId: String, systemName: String, folder: File?, query: String, values: Map<String, String>): List<CatalogGroup> {
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
                add(ActionItem("source_failure_${source.id}", "Couldn't search ${source.label}", subtitle = "It did not answer. Try again in a moment."))
                val technical = error.message?.trim().orEmpty().ifBlank { error::class.java.simpleName }
                // A Developer menu row (docs/SPEC.md 7, "Developer menu").
                if (dev.droidtop.library.settings.DeveloperMenu.on(context)) add(
                    NestedScreenItem(
                        "source_failure_detail_${source.id}", "Technical details", subtitle = "What the source reported, for its developer",
                        inline = CatalogScreen("source_failure_detail_screen_${source.id}", "Technical details", groups = {
                            listOf(CatalogGroup("detail", null, listOf(ActionItem("source_failure_text_${source.id}", technical))))
                        }),
                    ),
                )
                if (settings != null) add(NestedScreenItem("source_settings_${source.id}", "Open ${source.label} settings", inline = settings))
            })) },
        )
    }

    /** A result that has nowhere to go yet: says which folder is missing and where to add it (never a dead row). */
    fun noDestination(result: AcquireContentResult, systemName: String? = null) = CatalogScreen(
        "source_no_destination_${result.id}", result.title,
        groups = {
            listOf(CatalogGroup("info", null, listOf(ActionItem(
                "destination_missing",
                if (systemName.isNullOrBlank()) "No games folder to download into yet" else "No games folder for $systemName yet",
                subtitle = "Add one under Settings, Game folders, then open this result again.",
            ))))
        },
    )
}

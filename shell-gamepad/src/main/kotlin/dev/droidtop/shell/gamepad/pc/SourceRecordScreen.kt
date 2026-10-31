package dev.droidtop.shell.gamepad.pc

import android.content.Context
import dev.droidtop.library.GameLinks
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.RecordAction
import dev.droidtop.library.RecordRow
import dev.droidtop.library.RECORD_PREFS
import dev.droidtop.library.SYSTEM_KEY_PREFIX
import dev.droidtop.library.checkRow
import dev.droidtop.library.controlRows
import dev.droidtop.library.downloadRows
import dev.droidtop.library.downloadSystems
import dev.droidtop.library.signInRow
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.pluginhost.DownloadSystems
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A game's update, shown where the update row is (docs/SPEC.md 7g, "Facets in place"; owner, 2026-10-24,
 * Droidtop/tracker#639: "Clicking the update should present the facet containing the downloads", and "Facets go
 * where they fit. No clunky redirects"). It is the linked source's record as a settings screen, so the Updates row
 * pushes it in place in every host that draws Updates (Gaming's places, Standard's settings, the screen host, the
 * companion), and nothing starts an activity or opens the game's page. The rows are the record page's own
 * ([recordRows], [downloadRows]) and a download is the record page's one sequence ([getDownload]).
 */
object SourceRecordScreen {
    const val ID = "update_record"

    /**
     * The update of [entryId]'s game: its Downloads first, then its changelog, overview and details. [library] is asked
     * for only when the screen is opened.
     */
    fun forUpdate(library: (Context) -> Library, entryId: String, title: String): CatalogScreen = CatalogScreen(
        id = ID,
        title = title,
        subtitle = "Its update",
        groups = { context -> groups(context, library(context), entryId) },
        indexGroups = { _ -> emptyList() },
    )

    private suspend fun groups(context: Context, library: Library, entryId: String): List<CatalogGroup> {
        val facet = withContext(Dispatchers.IO) { facetOf(library, entryId) }
            ?: return listOf(
                CatalogGroup(
                    id = "update_record_none",
                    title = null,
                    items = listOf(
                        ActionItem(
                            id = "update_record_no_page",
                            title = "No source page for this update",
                            subtitle = "The source naming the newer version has no page droidtop can show; see the game's sources",
                        ),
                    ),
                ),
            )
        val answer = library.sourceRecord(facet.source.key, facet.externalId)
        val record = answer.record
        val signedOut = library.sourceSignedOut(facet.source.key)
        val needsCheck = library.sourceNeedsCheck(facet.source.key)
        val prefs = context.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE)
        val picked = prefs.getString(SYSTEM_KEY_PREFIX + facet.source.key, null)
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val versions = CatalogGroup(
            id = "update_record_versions",
            title = null,
            // Which is which, in words: the version on offer leads, the one here is under it (Droidtop/tracker#639).
            items = listOf(
                ActionItem(
                    id = "update_record_available",
                    title = GameUpdates.availableLabel(record?.version ?: facet.entry.availableUpdate ?: "a newer version"),
                    subtitle = GameUpdates.installedLabel(facet.installed),
                ),
            ),
        )
        if (record == null) {
            return listOf(
                versions,
                CatalogGroup(
                    id = "update_record_missing",
                    title = null,
                    items = listOf(
                        ActionItem(
                            id = "update_record_missing_row",
                            title = if (answer.unsupported) "${facet.source.label} has no update page" else "${facet.source.label} has no page for #${facet.externalId}",
                            subtitle = answer.failure,
                        ),
                    ),
                ),
            )
        }
        fun items(rows: List<RecordRow>, part: String): List<CatalogItem> = rows.mapIndexedNotNull { index, row ->
            itemOf(row, "update_record_${part}_$index", library, facet, record)
        }
        val head = listOfNotNull(checkRow(facet.source.label).takeIf { needsCheck }, signInRow(facet.source.label, record, signedOut).takeIf { !needsCheck })
        val offered = downloadSystems(record)
        val system = if (offered.size >= 2) {
            val current = picked?.takeIf { it in offered } ?: DownloadSystems.first(offered)
            ChoiceItem(
                id = "update_record_system",
                title = "System",
                subtitle = "Which system's links are listed",
                options = offered.map { ChoiceOption(it, DownloadSystems.LABELS[it] ?: it) },
                current = current,
                onSelect = { ctx, value ->
                    ctx.getSharedPreferences(RECORD_PREFS, Context.MODE_PRIVATE).edit().putString(SYSTEM_KEY_PREFIX + facet.source.key, value).apply()
                },
            )
        } else {
            null
        }
        // The record page's Downloads rows, less its System row: here the system is the choice above.
        val downloads = downloadRows(record, picked, dev.droidtop.library.DownloadPick.isSteamCopy(facet.entry)).filterNot { it.action == RecordAction.PickSystem }
        // A heading ("Extras", "Other builds") starts a group of its own, titled by it, rather than being a row
        // (Droidtop/tracker#859).
        val headed = downloads.withIndex().filter { it.value.heading }
        val firstHeading = headed.firstOrNull()?.index ?: downloads.size
        val sections = headed.mapIndexed { n, (at, heading) ->
            val end = headed.getOrNull(n + 1)?.index ?: downloads.size
            CatalogGroup(
                id = "update_record_downloads_$n",
                title = listOfNotNull(heading.title, heading.subtitle).joinToString(" \u00b7 "),
                items = items(downloads.subList(at + 1, end), "links_$n"),
            )
        }
        return listOf(
            versions,
            CatalogGroup(
                id = "update_record_downloads",
                title = RecordTab.DOWNLOADS.label,
                items = items(head, "head") + listOfNotNull(system) + items(downloads.subList(0, firstHeading), "links"),
            ),
        ) + sections + listOf(
            CatalogGroup(
                id = "update_record_changelog",
                title = RecordTab.CHANGELOG.label,
                items = listOf(TextBlockItem(id = "update_record_changelog_text", text = record.changelog ?: "The source gives no changelog")),
            ),
            CatalogGroup(
                id = "update_record_overview",
                title = RecordTab.OVERVIEW.label,
                items = items(controlRows(record), "controls") +
                    TextBlockItem(id = "update_record_overview_text", text = record.overview ?: "The source gives no description"),
            ),
            CatalogGroup(
                id = "update_record_info",
                title = RecordTab.INFO.label,
                // Change link is the game page's (its sources); here only what can be read or opened.
                items = items(recordRows(record, RecordTab.INFO, picked) { ms -> dateFormat.format(Date(ms)) }.filterNot { it.action == RecordAction.Relink }, "info"),
            ),
        )
    }

    /** One record row as a settings row: a download asks once more, then gets; the rest say what they hold. */
    private fun itemOf(row: RecordRow, id: String, library: Library, facet: UpdateFacet, record: UpdateSources.Record): CatalogItem? =
        when (val action = row.action) {
            is RecordAction.Download -> AsyncActionItem(
                id = id,
                title = row.title,
                subtitle = row.subtitle,
                confirmTitle = "Download this version? It lands beside your other versions",
                run = { _, onStatus -> getDownload(library, facet.source, record, action.download, facet.gameName, facet.entry, onStatus) },
            )
            RecordAction.GetBest -> AsyncActionItem(
                id = id,
                title = row.title,
                subtitle = row.subtitle,
                confirmTitle = "Get the update? droidtop picks the build and the host, and it lands beside your other versions",
                run = { ctx, onStatus -> getBest(library, facet.source, record, facet.gameName, facet.entry, dev.droidtop.library.HostOrder.get(ctx), onStatus) },
            )
            // A build's hosts to choose from, Try in order first (Droidtop/tracker#859), as on the record page.
            is RecordAction.Group -> NestedScreenItem(
                id = id,
                title = row.title,
                subtitle = row.subtitle,
                valueLabel = row.value?.let { value -> { _: Context -> value } },
                inline = CatalogScreen(
                    id = "${id}_hosts",
                    title = row.title,
                    subtitle = "Pick a host, or try them in order",
                    groups = {
                        listOf(
                            CatalogGroup(
                                id = "${id}_hosts_list",
                                title = null,
                                items = dev.droidtop.library.hostChoices(action.downloads).mapIndexed { n, choice ->
                                    AsyncActionItem(
                                        id = "${id}_host_$n",
                                        title = choice.title,
                                        subtitle = choice.subtitle,
                                        run = { ctx, onStatus ->
                                            if (choice.inOrder) {
                                                getGroup(library, facet.source, record, choice.downloads, facet.gameName, facet.entry, dev.droidtop.library.HostOrder.get(ctx), onStatus)
                                            } else {
                                                getDownload(library, facet.source, record, choice.downloads.single(), facet.gameName, facet.entry, onStatus)
                                            }
                                        },
                                    )
                                },
                            ),
                        )
                    },
                ),
            )
            RecordAction.SignIn -> AsyncActionItem(
                id = id,
                title = row.title,
                subtitle = row.subtitle,
                run = { _, _ -> if (library.signInToSource(facet.source.key)) "Signed in. Go back and open this again for the links" else "Not signed in" },
            )
            RecordAction.PassCheck -> AsyncActionItem(
                id = id,
                title = row.title,
                subtitle = row.subtitle,
                run = { _, _ -> if (library.passSourceCheck(facet.source.key)) "Passed. Go back and open this again" else "The check was not passed" },
            )
            // A web page is the person's own pick of a row, in a browser; never the update row's A.
            is RecordAction.Open -> ActionItem(
                id = id,
                title = row.title,
                value = row.value,
                run = { ctx ->
                    dev.droidtop.library.Links.open(ctx, action.url)
                },
            )
            // A switch is the settings switch; the source's answer is shown when the screen is opened again.
            is RecordAction.Control -> if (action.control.kind == UpdateSources.Control.Kind.TOGGLE) {
                ToggleItem(
                    id = id,
                    title = row.title,
                    subtitle = row.subtitle,
                    current = action.control.on,
                    onToggle = { _, on -> library.runSourceControl(facet.source.key, record.externalId, action.control, on) },
                )
            } else {
                AsyncActionItem(
                    id = id,
                    title = row.title,
                    subtitle = row.subtitle,
                    confirmTitle = row.title.takeIf { action.control.confirm },
                    run = { _, _ -> library.runSourceControl(facet.source.key, record.externalId, action.control, null).ifBlank { "Done" } },
                )
            }
            RecordAction.Relink, RecordAction.PickSystem -> null
            null -> ActionItem(id = id, title = row.title, subtitle = row.subtitle, value = row.value)
        }
}

/** What the update screen is for: the game, the source its update comes from and the record, and the version here. */
internal class UpdateFacet(
    val entry: LibraryEntry,
    val gameName: String,
    val source: UpdateSources.Source,
    val externalId: String,
    val installed: String?,
)

/** [entryId]'s game, the source its update comes from and the record, or null when no source page can show it. IO. */
internal suspend fun facetOf(library: Library, entryId: String): UpdateFacet? {
    val entries = library.backgroundScanState(LibraryKinds.GAMES).value ?: return null
    val group = LibraryGrouping.group(entries).firstOrNull { group -> group.displayEntry.id == entryId || entryId in group.entriesByPath }
        ?: return null
    val links: GameLinks = library.gameLinks(group.entriesByPath.keys) ?: return null
    val link = links.updateLink ?: return null
    val source = library.updateSources().firstOrNull { it.key == link.source } ?: return null
    return UpdateFacet(group.displayEntry, GameNaming.displayName(group.game.name), source, link.externalId, group.game.defaultVersion?.version)
}

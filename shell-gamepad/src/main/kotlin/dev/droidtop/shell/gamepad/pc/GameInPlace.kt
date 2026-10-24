package dev.droidtop.shell.gamepad.pc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameLinks
import dev.droidtop.library.GameNaming
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.settings.Notices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Gaming shows in place of navigating away (docs/SPEC.md 7g, "Facets in place"; Droidtop/tracker#639): the
 * downloads of [updateId]'s waiting update, its linked source's record at the Downloads tab, and over it, when asked,
 * [pageId]'s game page. Back closes the top one, so it is the page, then the downloads, then the row the person came
 * from. A game with no linked source to show has nothing to show in place but its page, which opens instead.
 */
@Composable
internal fun GameInPlace(
    library: Library,
    updateId: String?,
    pageId: String?,
    onOpenPage: (String) -> Unit,
    onCloseUpdate: () -> Unit,
    onClosePage: () -> Unit,
    onLaunch: (LibraryEntry) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    if (updateId != null) UpdateDownloads(library, updateId, onOpenPage, onCloseUpdate)
    if (pageId != null) {
        PcGameStandalone(library = library, entryId = pageId, onLaunch = onLaunch, onOpenDownloads = onOpenDownloads, onClose = onClosePage)
    }
}

/** The facet itself: what the update row's game is linked to, and its record page opened at Downloads. */
private class UpdateFacet(val entry: LibraryEntry, val gameName: String, val source: UpdateSources.Source, val externalId: String)

@Composable
private fun UpdateDownloads(library: Library, entryId: String, onOpenPage: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by library.backgroundScanState(LibraryKinds.GAMES).collectAsState()
    // Null while it is worked out; a facet with no source means there is nothing to show but the page.
    val facet by produceState<Result<UpdateFacet?>?>(null, entryId, entries) {
        val all = entries ?: return@produceState
        value = runCatching { withContext(Dispatchers.IO) { facetOf(library, all, entryId) } }
    }
    val found = facet ?: return
    LaunchedEffect(found) {
        if (found.getOrNull() == null) {
            onClose()
            onOpenPage(entryId)
        }
    }
    val shown = found.getOrNull() ?: return
    SourceRecordPage(
        library = library,
        source = shown.source,
        externalId = shown.externalId,
        gameName = shown.gameName,
        newest = shown.entry,
        scope = scope,
        say = { line -> Notices.flash(line, context = context) },
        // The link is changed on the game's own page, where its sources are.
        onRelink = { onOpenPage(entryId) },
        onOpen = { url ->
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        onClose = onClose,
        startTab = RecordTab.DOWNLOADS,
        onOpenGame = { onOpenPage(entryId) },
    )
}

/** [entryId]'s game, the source its update comes from and the record, or null when no source page can show it. IO. */
private suspend fun facetOf(library: Library, entries: List<LibraryEntry>, entryId: String): UpdateFacet? {
    val group = LibraryGrouping.group(entries).firstOrNull { group -> group.displayEntry.id == entryId || entryId in group.entriesByPath }
        ?: return null
    val links: GameLinks = library.gameLinks(group.entriesByPath.keys) ?: return null
    val link = links.updateLink ?: return null
    val source = library.updateSources().firstOrNull { it.key == link.source } ?: return null
    return UpdateFacet(group.displayEntry, GameNaming.displayName(group.game.name), source, link.externalId)
}

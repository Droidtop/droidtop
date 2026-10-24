package dev.droidtop.shell.gamepad.pc

import androidx.compose.runtime.Composable
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry

/**
 * A game's page shown over the row that asked for it, inside Gaming (docs/SPEC.md 7g, "Facets in place";
 * Droidtop/tracker#639): a job row's game, so Back returns to the row. An update row needs nothing of the shell: its
 * update is a settings screen ([SourceRecordScreen]) pushed in place by whatever draws Updates.
 */
@Composable
internal fun GameInPlace(
    library: Library,
    pageId: String?,
    onClosePage: () -> Unit,
    onLaunch: (LibraryEntry) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    if (pageId != null) {
        PcGameStandalone(library = library, entryId = pageId, onLaunch = onLaunch, onOpenDownloads = onOpenDownloads, onClose = onClosePage)
    }
}

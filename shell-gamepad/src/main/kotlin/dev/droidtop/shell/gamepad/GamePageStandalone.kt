package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.pc.PcGameStandalone

/** Whether [entry] has a game page outside Gaming: a PC or engine game, or a console game (docs/SPEC.md 7j). */
fun hasGamePage(entry: LibraryEntry): Boolean = entry.isPcOrEngineGame || entry.kind == LibraryEntryKind.CONSOLE_ROM

/**
 * A game's page outside the Gaming shell, the one Gaming opens (docs/SPEC.md 7j "Places in every mode",
 * Droidtop/tracker#349): a PC or engine game's page and menu ([PcGameStandalone]), or a console game's
 * detail, the same screen Gaming's Info opens, in a window of its own. Standard's Games grid and Desktop's
 * Start menu host it, so a console game can be scraped, edited, have its media viewed or be put in a
 * collection from every mode, not only played. [onClose] runs when the page closes, or when the library
 * has no such game.
 */
@Composable
fun GamePageStandalone(
    library: Library,
    entryId: String,
    onLaunch: (LibraryEntry) -> Unit,
    onOpenDownloads: () -> Unit,
    onClose: () -> Unit,
) {
    val entries by library.backgroundScanState(LibraryKinds.GAMES).collectAsState()
    val all = entries ?: return
    val entry = all.firstOrNull { it.id == entryId }
    LaunchedEffect(entry) { if (entry == null || !hasGamePage(entry)) onClose() }
    if (entry == null) return
    if (entry.isPcOrEngineGame) {
        PcGameStandalone(library = library, entryId = entryId, onLaunch = onLaunch, onOpenDownloads = onOpenDownloads, onClose = onClose)
        return
    }
    if (entry.kind != LibraryEntryKind.CONSOLE_ROM) return
    ShellDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
            Box(Modifier.fillMaxSize().groundBackground()) {
                EntryDetailScreen(
                    entry = entry,
                    library = library,
                    onLaunch = {
                        onLaunch(entry)
                        onClose()
                    },
                    onClose = onClose,
                )
            }
        }
    }
}

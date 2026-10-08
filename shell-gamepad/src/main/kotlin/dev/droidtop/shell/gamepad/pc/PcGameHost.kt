package dev.droidtop.shell.gamepad.pc

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PartProgress
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.currentShellWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The folded library and, per drawn game, every folder and store row behind it (docs/SPEC.md 7m). */
internal class FoldedPcLibrary(
    val games: List<LibraryEntry>,
    val siblings: Map<String, List<LibraryEntry>>,
    /** A multi-part game's card id to the entry Play starts (the first part not finished, docs/SPEC.md 7n); only games where that differs from the card. */
    val continuing: Map<String, LibraryEntry>,
)

/** ONE card per game, not per folder (docs/SPEC.md 7m). Call off the main thread: it reads the games roots and part progress. */
internal fun foldPcLibrary(context: Context, entries: List<LibraryEntry>): FoldedPcLibrary {
    val groups = LibraryGrouping.group(entries, PartProgress.finished(context), GamesRoots.current(context).map { it.absolutePath })
    return FoldedPcLibrary(
        games = groups.map { it.displayEntry },
        siblings = groups.associate { group -> group.displayEntry.id to group.entriesByPath.values.toList() },
        continuing = groups
            .mapNotNull { group -> group.continueEntry?.takeIf { it.id != group.displayEntry.id }?.let { group.displayEntry.id to it } }
            .toMap(),
    )
}

/**
 * A PC game's primary action, one rule for every surface that has one (docs/SPEC.md 7i "Capsules and the
 * primary action", Droidtop/tracker#349): a store game that is not installed or has an update stops on the
 * free-space offer ([offer], drawn by [PcLaunchOfferSheet]); a store download already running goes to the
 * store's queue; anything else launches through the caller's [onLaunch]. The PC Games tab and the hosts
 * outside Gaming (Standard's Games grid, Desktop's Start menu) all use it, so a game installs the same way
 * from every mode.
 */
class PcLaunch internal constructor(
    internal val offer: MutableState<StoreInstallOffer?>,
    val launch: (LibraryEntry) -> Unit,
    internal val proceed: (LibraryEntry, String) -> Unit,
)

@Composable
fun rememberPcLaunch(onLaunch: (LibraryEntry) -> Unit, onOpenDownloads: () -> Unit): PcLaunch {
    val context = LocalContext.current
    val downloads by StoreDownloads.active.collectAsState()
    val scope = rememberCoroutineScope()
    val currentLaunch by rememberUpdatedState(onLaunch)
    val currentOpenDownloads by rememberUpdatedState(onOpenDownloads)
    val offer = remember { mutableStateOf<StoreInstallOffer?>(null) }
    return remember(offer, scope) {
        PcLaunch(
            offer = offer,
            launch = { entry ->
                when (val stage = storeStageOf(entry, entry.downloadKey()?.let { downloads[it] })) {
                    StoreStage.INSTALL, StoreStage.UPDATE -> offer.value = StoreInstallOffer(entry, stage)
                    null -> currentLaunch(entry)
                    else -> say(context, continueStoreDownload(context, entry, stage) { currentOpenDownloads() })
                }
            },
            // The install offer was taken: the game's store starts its job here.
            proceed = { entry, volumePath ->
                val own = entry.ownStore()
                if (own == null) {
                    say(context, NO_STORE_LINE)
                } else {
                    scope.launch { say(context, startOwnStoreInstall(context, entry, own, volumePath)) }
                }
            },
        )
    }
}

/**
 * The free-space offer before a store install or update: its own window over whatever opened it, the one
 * place the volume is chosen (Droidtop/tracker#227).
 */
@Composable
fun PcLaunchOfferSheet(launch: PcLaunch) {
    StoreInstallOfferSheet(
        offer = launch.offer.value,
        onProceed = { o, volumePath ->
            launch.offer.value = null
            launch.proceed(o.entry, volumePath)
        },
        onDismiss = { launch.offer.value = null },
    )
}

private fun say(context: Context, line: String?) {
    line?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
}

/**
 * A PC or engine game's page and its menu, outside the Gaming shell (docs/SPEC.md 7i "The game page",
 * Droidtop/tracker#349): the same [PcGamePage], [PcGameMenu] and install offer the PC Games tab opens,
 * hosted by Standard's Games grid and Desktop's Start menu so a game can be installed, set up, given a
 * runner or favourited from every mode, not only played. [entryId] is any folder or store row of the
 * game; the page shows the game it belongs to. Nothing is drawn until the library is folded, and
 * [onClose] runs when the page and its menu are both closed, or when the library has no such game.
 *
 * The page and the menu are windows of their own (Dialogs), so a host only places this composable;
 * the one input pipeline runs inside them as it does in Gaming.
 */
@Composable
fun PcGameStandalone(
    library: Library,
    entryId: String,
    onLaunch: (LibraryEntry) -> Unit,
    onOpenDownloads: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val entries by library.backgroundScanState(LibraryKinds.GAMES).collectAsState()
    var progressToken by remember { mutableIntStateOf(0) }
    val pcEntries = remember(entries) { entries.orEmpty().filter { it.isPcOrEngineGame } }
    val folded by produceState<FoldedPcLibrary?>(null, pcEntries, progressToken) {
        if (entries != null) value = withContext(Dispatchers.Default) { foldPcLibrary(context, pcEntries) }
    }
    var pageOpen by remember(entryId) { mutableStateOf(true) }
    var menuOpen by remember(entryId) { mutableStateOf(false) }
    // The menu can move sideways to another folder of the same game (docs/SPEC.md 7m).
    var menuEntryId by remember(entryId) { mutableStateOf<String?>(null) }
    val fold = folded
    val game = fold?.let { f ->
        f.games.firstOrNull { it.id == entryId }
            ?: f.siblings.entries.firstOrNull { (_, rows) -> rows.any { it.id == entryId } }?.key?.let { id -> f.games.firstOrNull { it.id == id } }
    }
    LaunchedEffect(fold, game, pageOpen, menuOpen) {
        if (fold != null && (game == null || (!pageOpen && !menuOpen))) onClose()
    }
    val scope = rememberCoroutineScope()
    val launch = rememberPcLaunch(
        onLaunch = { entry -> onLaunch(fold?.continuing?.get(entry.id) ?: entry) },
        onOpenDownloads = onOpenDownloads,
    )
    if (game == null || fold == null) return
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        if (pageOpen) {
            PcGamePage(
                entry = game,
                siblings = fold.siblings[game.id] ?: listOf(game),
                onPlay = { launch.launch(game) },
                onToggleFavorite = { scope.launch { library.toggleFavorite(game) } },
                onOpenOptions = {
                    menuEntryId = game.id
                    menuOpen = true
                },
                onClose = { pageOpen = false },
                library = library,
            )
        }
        val menuEntry = menuEntryId?.let { id -> pcEntries.firstOrNull { it.id == id } } ?: game
        if (menuOpen) {
            PcGameMenu(
                entry = menuEntry,
                library = library,
                onLaunch = { launch.launch(menuEntry) },
                onClose = { menuOpen = false },
                siblings = pcEntries,
                onOpenOther = { menuEntryId = it.id },
                onProgressChanged = { progressToken++ },
                onOpenPage = {
                    menuOpen = false
                    pageOpen = true
                },
                onOpenDownloads = onOpenDownloads,
            )
        }
        PcLaunchOfferSheet(launch)
    }
}

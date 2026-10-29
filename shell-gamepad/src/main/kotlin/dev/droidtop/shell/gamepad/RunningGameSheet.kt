package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameNaming
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.QuitResult
import dev.droidtop.library.consoles.GameEmulatorChoice
import dev.droidtop.library.itemName
import dev.droidtop.library.message
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.library.settings.GameControls
import dev.droidtop.library.settings.GameRow
import dev.droidtop.library.settings.GameRunner
import dev.droidtop.library.settings.UiModeRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a surface that draws the running game's sheet gives it: the library, the one launch path (Resume in the shell,
 * and Restart once the game really ended), and a console system's search where the surface has one. Gaming provides
 * it around its sections, and the in-game overlay provides its own; a surface without one never offers the sheet.
 */
internal class RunningGameHost(
    val library: Library,
    val launch: (LibraryEntry) -> Unit,
    val searchSystem: ((systemId: String, systemName: String) -> Unit)? = null,
)

internal val LocalRunningGameHost = staticCompositionLocalOf<RunningGameHost?> { null }

/** True inside the sheet's own Game options, so the game menu it opens does not lead with the sheet again. */
private val LocalInRunningGameSheet = staticCompositionLocalOf { false }

/**
 * L2 on the game that is running (Droidtop/tracker#740; the owner, 2026-10-30: "r2 is the global menu. L2 is
 * everything we can do contextually for this app/game/etc"): [RunningGameSheet] first, its Game options row opening
 * [content], the game's own menu. Any other game, or no host, is [content] at once. The game menus wrap themselves
 * in this, so every L2 and long press on the running game in Gaming opens the same sheet the overlay does.
 */
@Composable
internal fun RunningGameFirst(entry: LibraryEntry, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalRunningGameHost.current
    val running by dev.droidtop.library.LaunchDisplay.running.collectAsState()
    var options by remember(entry.id) { mutableStateOf(false) }
    if (host == null || options || LocalInRunningGameSheet.current || running?.context?.gameId != entry.id) {
        content()
        return
    }
    RunningGameSheet(
        entry = entry,
        onResume = {
            onDismiss()
            host.launch(entry)
        },
        onGameOptions = { options = true },
        onDismiss = onDismiss,
    )
}

/** Opens the game menu from the sheet: inside it the menu is itself, not the sheet again. */
@Composable
internal fun GameOptionsOfRunningGame(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInRunningGameSheet provides true, content = content)
}

/**
 * The running game's sheet, one [ActionSheet] wherever it is drawn: L2 on the running game in Gaming, and HOME + L2
 * over it (docs/SPEC.md "In-game overlays: HOME chords"). It is what the Quick Menu's Game section held, which R2 no
 * longer has (Droidtop/tracker#740), with the overlay's own rows:
 *
 * - Resume ([onResume]), and Pause the game for a console game (RetroArch's PAUSE_TOGGLE, sent only on this press);
 * - Emulator, the game's own choice ([GameEmulatorChoice], the companion's model too);
 * - Report a problem, Mouse mode and Controls for a game in droidtop's own screen;
 * - Screenshot, which closes the sheet first so the picture is of the game (Droidtop/tracker#626);
 * - Guides; the console's own search where the host has one; Game options, the game's own menu ([onGameOptions]);
 * - Quit, Restart and Kill, which ask in the one [ConfirmDialog] when [GameControls.asks] says so, say their outcome on
 *   the row, and close the sheet once the game really ended; Restart starts it again only then.
 *
 * The rows a mode shows are [GameControls.rows]' (Kid and Kiosk get fewer), the same decision as the companion's.
 */
@Composable
internal fun RunningGameSheet(
    entry: LibraryEntry,
    onResume: () -> Unit,
    onGameOptions: () -> Unit,
    onDismiss: () -> Unit,
    resumeDetail: String? = null,
) {
    val host = LocalRunningGameHost.current ?: return
    val library = host.library
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CompanionPrefs.load(context.applicationContext) }
    }
    val settings by CompanionPrefs.settings.collectAsState()
    val uiMode by UiModeRefresh.mode.collectAsState()
    val shown = remember(uiMode) { GameControls.rows(uiMode, GameRunner.LOCAL).toSet() }
    var pending by remember(entry.id) { mutableStateOf<PendingConfirm?>(null) }
    // What the last end or Pause said, on its own row.
    var said by remember(entry.id) { mutableStateOf<Pair<GameRow?, String>?>(null) }
    var guidesOpen by remember(entry.id) { mutableStateOf(false) }
    val emulators = rememberSystemEmulators(entry)
    var emulatorChoice by remember(entry.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id) { emulatorChoice = library.getMetadataForEditing(entry)?.altEmulator }
    // Controller mouse mode, Controls and Report a problem (docs/SPEC.md 5b, 7e2): only for a game in droidtop's own
    // screen that offers them.
    val ownScreen = dev.droidtop.library.OwnGameScreens.current?.takeIf { it.entryId == entry.id }
    var padMouse by remember(entry.id) { mutableStateOf(ownScreen?.padMouse) }
    var controls by remember(entry.id) { mutableStateOf(ownScreen?.controls) }
    var reportLine by remember(entry.id) { mutableStateOf<String?>(null) }
    var paused by remember(entry.id) { mutableStateOf<String?>(null) }

    if (guidesOpen) {
        dev.droidtop.shell.gamepad.pc.GuidesScreen(
            gameId = entry.id,
            gameName = GameNaming.displayName(entry.title),
            folder = dev.droidtop.library.guides.GuideContent.folderOf(entry),
            onClose = { guidesOpen = false },
        )
        return
    }

    fun ending(row: GameRow, idle: String, end: suspend () -> QuitResult, then: () -> Unit = {}): ActionRow {
        val title = GameControls.label(row, GameRunner.LOCAL)
        val run = {
            said = row to "Ending..."
            scope.launch {
                val outcome = end()
                said = row to outcome.message
                if (outcome is QuitResult.Ended) {
                    onDismiss()
                    then()
                }
            }
            Unit
        }
        return ActionRow(title, said?.takeIf { it.first == row }?.second ?: idle, danger = true) {
            if (GameControls.asks(row, uiMode, settings.ask)) {
                pending = PendingConfirm(GameControls.question(row, GameRunner.LOCAL, entry.title), title, run)
            } else {
                run()
            }
        }
    }

    val rows = buildList<ActionSheetItem> {
        if (GameRow.RESUME in shown) add(ActionRow("Resume", resumeDetail, onSelect = onResume))
        if (entry.systemId != null) {
            add(
                ActionRow("Pause the game", paused ?: "Tells RetroArch to pause or carry on") {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { dev.droidtop.pluginhost.RetroArchCommands.send("PAUSE_TOGGLE", 0) }
                        }
                        paused = "Sent to RetroArch. Press again to carry on"
                    }
                },
            )
        }
        // Only for a console game with something installed to choose from; it applies from the next start.
        if (GameRow.EMULATOR in shown && emulators != null && GameEmulatorChoice.offered(emulators)) {
            add(
                ActionRow("Emulator", GameEmulatorChoice.summary(emulators, emulatorChoice)) {
                    val next = GameEmulatorChoice.next(emulators, emulatorChoice)
                    scope.launch { if (GameEmulatorChoice.save(library, entry, next)) emulatorChoice = next }
                },
            )
        }
        if (ownScreen?.canReport == true) {
            add(
                ActionRow("Report a problem", reportLine ?: "Saves what the game printed and its logs, then you choose where to send them") {
                    reportLine = "Saving the report..."
                    ownScreen.reportProblem { line -> scope.launch { reportLine = line } }
                },
            )
        }
        padMouse?.let { on ->
            add(
                ActionRow("Mouse mode", if (on) "On. Both stick clicks held also turn it off" else "Off") {
                    ownScreen?.setPadMouse(!on)
                    padMouse = !on
                },
            )
        }
        controls?.let { line ->
            add(ActionRow("Controls", line) { controls = ownScreen?.nextControls() ?: line })
        }
        // One press during play (Droidtop/tracker#626): the sheet closes first so the shot is of the game, on the
        // screen the game runs on; a flash says where it went.
        if (GameRow.SCREENSHOT in shown) {
            add(
                ActionRow("Screenshot", "Closes this menu and saves a picture of the game") {
                    onDismiss()
                    val display = dev.droidtop.library.LaunchDisplay.parkedDisplayId ?: android.view.Display.DEFAULT_DISPLAY
                    dev.droidtop.runtime.Capture.takeSoon(context, display) { shot ->
                        dev.droidtop.library.settings.Notices.flash(shot.message, context = context.applicationContext)
                    }
                },
            )
        }
        add(ActionRow("Guides", "Walkthroughs and readmes, with your place kept") { guidesOpen = true })
        // A console game's system, searched (owner, 2026-10-14: "We also want per-console search items").
        val search = host.searchSystem
        emulators?.system?.takeIf { search != null && entry.kind == LibraryEntryKind.CONSOLE_ROM }?.let { system ->
            add(
                ActionRow(dev.droidtop.shell.gamepad.query.SearchEntry.systemLabel(system.displayName)) {
                    onDismiss()
                    search?.invoke(system.id, system.displayName)
                },
            )
        }
        add(ActionRow("Game options", "This game's own menu") { onGameOptions() })
        if (GameRow.QUIT in shown) add(ending(GameRow.QUIT, "Ends the game the way it ends itself", { library.quitRunning(context, entry) }))
        if (GameRow.RESTART in shown) {
            add(ending(GameRow.RESTART, "Ends it, then starts it again", { library.quitRunning(context, entry) }) { host.launch(entry) })
        }
        if (GameRow.KILL in shown) add(ending(GameRow.KILL, "Force-stops it; unsaved progress is lost", { library.killRunning(context, entry) }))
    }
    ActionSheet(
        title = GameNaming.displayName(entry.title),
        subtitle = entry.kind.itemName(),
        rows = rows,
        onDismiss = onDismiss,
    )
    pending?.let { ask ->
        ConfirmDialog(ask.question, ask.action, onConfirm = { pending = null; ask.run() }, onCancel = { pending = null })
    }
}

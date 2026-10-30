package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.display.secondScreenScroll
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.GameEmulatorChoice
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.library.settings.GameControls
import dev.droidtop.library.settings.GameRow
import dev.droidtop.library.settings.GameRunner
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.runtime.systemstatus.PerformanceOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The question a companion row asks before it acts (docs/SPEC.md "The companion's tabs"): at most one at a time.
 * The companion is touch only, so a pad press that still reaches its window while a question is open is taken as
 * Cancel, and the row says how to go on ([cancelByKey], from the companion Activity's key dispatch).
 */
internal object CompanionConfirm {
    data class Question(val text: String, val action: String, val run: () -> Unit)

    val open = MutableStateFlow<Question?>(null)
    val cancelled = MutableStateFlow<String?>(null)

    fun ask(question: Question) {
        cancelled.value = null
        open.value = question
    }

    fun cancel() {
        open.value = null
    }

    /** A key on the companion while a question is open closes it as Cancel; true when it did. */
    fun cancelByKey(event: android.view.KeyEvent): Boolean {
        val question = open.value ?: return false
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            open.value = null
            cancelled.value = "Cancelled. Tap ${question.action} on this screen to ${question.action.lowercase()}."
        }
        return true
    }
}

/** What a running entry's runner is: a stream disconnects (windowcast), everything else is a local game. */
internal fun runnerOf(entry: LibraryEntry?): GameRunner =
    if (entry?.kind == LibraryEntryKind.REMOTE_STREAM) GameRunner.STREAM else GameRunner.LOCAL

/**
 * The Game tab, on the bar while a game runs (docs/SPEC.md "The companion's tabs", slice C8; dense since
 * Droidtop/tracker#835 part 2): the header (art, name, this session's play time), then one list of [GameActionRow]s
 * from the rows [GameControls] gives for the mode and the runner: Resume (the one filled row), Pause the game for a
 * console game, the emulator, the overlay level, the performance mode and Screenshot, values on the right; Mouse mode,
 * Controls and Report a problem for a game in droidtop's own screen; Guides ([CompanionGuides]); rows from plugins
 * ([CompanionGamePluginRows]); and under Stop, Quit (the runner's own label: Disconnect for a stream), Restart and
 * Kill (not for a stream) in the danger tone. The running game's L2 sheet has the same set. Quit, Restart and Kill ask first while Ask
 * before stopping is on, and always in Kid and Kiosk;
 * the question starts on Keep playing, with the two answers large and well apart.
 */
@Composable
internal fun CompanionGameTab() {
    val context = LocalContext.current
    val running by LaunchDisplay.running.collectAsState()
    val entries by CompanionState.libraryEntries.collectAsState()
    val settings by CompanionPrefs.settings.collectAsState()
    val uiMode by UiModeRefresh.mode.collectAsState()
    val runningId = running?.context?.gameId
    val entry = remember(runningId, entries) { runningId?.let { id -> entries.firstOrNull { it.id == id } } }
    val session = running
    // The running game's guides fill the tab while open (docs/SPEC.md 7i "Guides"): a pane beside the game.
    var readingGuides by remember(runningId) { mutableStateOf(false) }
    if (readingGuides && entry != null && session != null) {
        CompanionGuides(entry, onClose = { readingGuides = false })
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entry == null || session == null) {
            CompanionNote("Nothing running")
            return@Column
        }
        val runner = runnerOf(entry)
        val colors = MaterialTheme.colorScheme
        val played by produceState(sessionLabel(session.sinceEpochMs, System.currentTimeMillis()), session.sinceEpochMs) {
            while (true) {
                value = sessionLabel(session.sinceEpochMs, System.currentTimeMillis())
                kotlinx.coroutines.delay(30_000)
            }
        }
        // The header: art (Coil reads the cache off the main thread), name, play time.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CompanionCapsuleArt(entry, 88.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.companion_game_playing, played), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        val question by CompanionConfirm.open.collectAsState()
        val cancelled by CompanionConfirm.cancelled.collectAsState()
        val open = question
        if (open != null) {
            CompanionAsk(open.text, "Keep playing", open.action, onSafe = { CompanionConfirm.cancel() }, onAction = {
                CompanionConfirm.cancel()
                open.run()
            })
            return@Column
        }
        cancelled?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        // What the last Quit or Kill came to, here where it was pressed: "Ending…", then why it did not end.
        val stopStatus by CompanionState.stopStatus.collectAsState()
        stopStatus?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        var overlayVersion by remember { mutableIntStateOf(0) }
        val overlay by PerformanceOverlay.level.collectAsState()
        val performanceItem by produceState<dev.droidtop.library.settings.AsyncActionItem?>(null, overlayVersion) {
            value = withContext(Dispatchers.IO) { GamingSettingsCatalog.performanceModeItem() }
        }
        fun act(row: GameRow, run: () -> Unit) {
            if (GameControls.asks(row, uiMode, settings.ask)) {
                CompanionConfirm.ask(CompanionConfirm.Question(GameControls.question(row, runner, entry.title), GameControls.label(row, runner), run))
            } else {
                run()
            }
        }
        // One dense list (Droidtop/tracker#835 part 2): Resume the one filled row, then what the game can be told or
        // set, then the ending rows together at the bottom in the danger tone. The same set as the running game's L2
        // sheet (Pause, Mouse mode, Controls, Report a problem), plus what only the companion has; nothing removed.
        val rows = GameControls.rows(uiMode, runner)
        val ending = setOf(GameRow.QUIT, GameRow.RESTART, GameRow.KILL)
        val stopping = stopStatus != null
        rows.filter { it !in ending }.forEach { row ->
            val label = GameControls.label(row, runner)
            when (row) {
                // While a Quit or Kill is ending the game, Resume is not the thing to press.
                GameRow.RESUME -> GameActionRow(label, tone = if (stopping) RowTone.PLAIN else RowTone.PRIMARY) { CompanionState.onLaunchEntry?.invoke(entry) }
                GameRow.EMULATOR -> CompanionEmulatorChoice(entry)
                GameRow.OVERLAY -> GameActionRow(label, value = overlay.label) {
                    if (PerformanceOverlay.canDraw(context)) PerformanceOverlay.setLevel(context, overlay.next())
                }
                GameRow.PERFORMANCE_MODE -> performanceItem?.let { item ->
                    CompanionCatalogItems(listOf(item), onChanged = { overlayVersion++ })
                }
                GameRow.SCREENSHOT -> CompanionScreenshotRow(label)
                else -> Unit
            }
            // Pause right after Resume, for a console game: RetroArch's PAUSE_TOGGLE, sent only on this press.
            if (row == GameRow.RESUME && entry.systemId != null) CompanionPauseRow()
        }
        CompanionOwnScreenRows(entry)
        GameActionRow("Guides") { readingGuides = true }
        // Rows from plugins whose panel declares the game ability (slice C9): RetroArch's save and load, a stream's controls.
        CompanionGamePluginRows(entry)
        // "Save and load from here: Set up" while RetroArch runs it and a step is missing (slice C11).
        CompanionRetroArchSetup(entry, rememberCompanionPanels(pluginMode(LocalCompanionMode.current)))
        val endings = rows.filter { it in ending }
        if (endings.isNotEmpty()) {
            Text(stringResource(R.string.companion_game_stop), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            endings.forEach { row ->
                val label = GameControls.label(row, runner)
                GameActionRow(label, tone = RowTone.DANGER) {
                    when (row) {
                        GameRow.QUIT -> act(row) { CompanionState.quit(context, entry) }
                        GameRow.RESTART -> act(row) { CompanionState.onRestartEntry?.invoke(entry) }
                        else -> act(row) { CompanionState.quit(context, entry, kill = true) }
                    }
                }
            }
        }
    }
}

/**
 * The game's own emulator: the model the Quick Menu's Game section cycles with A ([GameEmulatorChoice]), drawn for
 * touch: the row says what will run and where that was decided, and a tap lays the choices out under it, Follow the
 * system first. Only for a console game with an emulator installed to choose from; it applies from the next start.
 */
@Composable
private fun CompanionEmulatorChoice(entry: LibraryEntry) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val emulators = dev.droidtop.shell.gamepad.rememberSystemEmulators(entry)
    // The library is built once per process, and the stored choice is a database read: both off the main thread.
    val library by produceState<dev.droidtop.library.Library?>(null) {
        value = withContext(Dispatchers.IO) { LibraryCore.library(context.applicationContext) }
    }
    var choice by remember(entry.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id, library) { library?.let { choice = it.getMetadataForEditing(entry)?.altEmulator } }
    var open by remember(entry.id) { mutableStateOf(false) }
    val loaded = emulators ?: return
    if (!GameEmulatorChoice.offered(loaded)) return
    GameActionRow("Emulator", value = GameEmulatorChoice.summary(loaded, choice)) { open = !open }
    if (!open) return
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GameEmulatorChoice.options(loaded, choice).forEach { option ->
            CompanionTile(
                onClick = {
                    open = false
                    val lib = library ?: return@CompanionTile
                    scope.launch { if (GameEmulatorChoice.save(lib, entry, option.id)) choice = option.id }
                },
                modifier = Modifier.fillMaxWidth().semantics { selected = option.current },
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(if (option.current) colors.primaryContainer else colors.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(option.label, style = MaterialTheme.typography.titleSmall, color = if (option.current) colors.onPrimaryContainer else colors.onSurface)
                    Text(option.detail, style = MaterialTheme.typography.bodySmall, color = if (option.current) colors.onPrimaryContainer else colors.onSurfaceVariant)
                }
            }
        }
        CompanionNote("From the next start")
    }
}

/**
 * Screenshot (slice C15): the game's screen through [dev.droidtop.runtime.Capture], then Share. The game's screen is the
 * main one, where the shell launched it.
 */
@Composable
private fun CompanionScreenshotRow(label: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<dev.droidtop.runtime.Capture.Shot?>(null) }
    var working by remember { mutableStateOf(false) }
    GameActionRow(label, value = if (working) "Taking…" else null) {
        if (working) return@GameActionRow
        working = true
        scope.launch {
            val display = ForegroundShell.current()?.window?.decorView?.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            shot = dev.droidtop.runtime.Capture.take(context.applicationContext, display)
            working = false
        }
    }
    shot?.let { taken ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(taken.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            taken.uri?.let { uri -> CompanionPill("Share") { runCatching { context.startActivity(dev.droidtop.runtime.Capture.shareIntent(uri)) } } }
        }
    }
}

/** How a [GameActionRow] reads: the one filled row (Resume), a plain row, or a row that ends the game. */
private enum class RowTone { PRIMARY, PLAIN, DANGER }

/**
 * The Game page's one row (Droidtop/tracker#835 part 2): 48dp, the label on the left and its [value] on the right, so
 * a setting reads at a glance without a second line. [tone] fills Resume and draws the ending rows in the danger tone.
 */
@Composable
private fun GameActionRow(label: String, value: String? = null, tone: RowTone = RowTone.PLAIN, onClick: () -> Unit) {
    CompanionTile(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        val colors = MaterialTheme.colorScheme
        val ink = when (tone) {
            RowTone.PRIMARY -> colors.onPrimary
            RowTone.DANGER -> colors.error
            RowTone.PLAIN -> colors.onSurface
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(if (tone == RowTone.PRIMARY) colors.primary else colors.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (tone == RowTone.PRIMARY) ink else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Pause the game, for a console game: RetroArch's PAUSE_TOGGLE over its command port, sent only on this press. */
@Composable
private fun CompanionPauseRow() {
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf(false) }
    GameActionRow("Pause the game", value = if (sent) "Sent" else null) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { dev.droidtop.pluginhost.RetroArchCommands.send("PAUSE_TOGGLE", 0) } }
            sent = true
        }
    }
}

/**
 * Mouse mode, Controls and Report a problem, for a game in droidtop's own screen that offers them (docs/SPEC.md 5b,
 * 7e2): the rows the running game's L2 sheet has, so the companion has them too.
 */
@Composable
private fun CompanionOwnScreenRows(entry: LibraryEntry) {
    val scope = rememberCoroutineScope()
    val ownScreen = dev.droidtop.library.OwnGameScreens.current?.takeIf { it.entryId == entry.id } ?: return
    var padMouse by remember(entry.id) { mutableStateOf(ownScreen.padMouse) }
    var controls by remember(entry.id) { mutableStateOf(ownScreen.controls) }
    var reportLine by remember(entry.id) { mutableStateOf<String?>(null) }
    padMouse?.let { on ->
        GameActionRow("Mouse mode", value = if (on) "On" else "Off") {
            ownScreen.setPadMouse(!on)
            padMouse = !on
        }
    }
    controls?.let { line -> GameActionRow("Controls", value = line) { controls = ownScreen.nextControls() ?: line } }
    if (ownScreen.canReport) {
        GameActionRow("Report a problem", value = reportLine) {
            reportLine = "Saving…"
            ownScreen.reportProblem { said -> scope.launch { reportLine = said } }
        }
    }
}

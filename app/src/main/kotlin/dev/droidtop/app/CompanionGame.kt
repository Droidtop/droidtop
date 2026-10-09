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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
import dev.droidtop.runtime.tasks.TaskManager
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
 * The Game tab, on the bar while a game runs (docs/SPEC.md "The companion's tabs", slice C8): the header (art, name,
 * this session's play time), then the rows [GameControls] gives for the mode and the runner: Resume, Quit (the
 * runner's own label: Disconnect for a stream), Restart and Kill (not for a stream), the overlay level and the
 * performance mode, then rows from plugins ([CompanionGamePluginRows]). Quit, Restart and Kill ask first while Ask
 * before stopping is on, and always in Kid and Kiosk;
 * the question starts on Keep playing, with the two answers large and well apart.
 */
@Composable
internal fun CompanionGameTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by LaunchDisplay.running.collectAsState()
    val entries by CompanionState.libraryEntries.collectAsState()
    val settings by CompanionPrefs.settings.collectAsState()
    val uiMode by UiModeRefresh.mode.collectAsState()
    val runningId = running?.context?.gameId
    val entry = remember(runningId, entries) { runningId?.let { id -> entries.firstOrNull { it.id == id } } }
    val session = running
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
                Text("Playing · $played", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        val question by CompanionConfirm.open.collectAsState()
        val cancelled by CompanionConfirm.cancelled.collectAsState()
        val open = question
        if (open != null) {
            CompanionQuestion(open)
            return@Column
        }
        cancelled?.let {
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
        GameControls.rows(uiMode, runner).forEach { row ->
            val label = GameControls.label(row, runner)
            when (row) {
                GameRow.RESUME -> GameButton(label, primary = true) { CompanionState.onLaunchEntry?.invoke(entry) }
                GameRow.QUIT -> GameButton(label) { act(row) { CompanionState.onQuitEntry?.invoke(entry) } }
                GameRow.RESTART -> GameButton(label) { act(row) { CompanionState.onRestartEntry?.invoke(entry) } }
                GameRow.KILL -> GameButton(label) {
                    act(row) {
                        val pkg = LaunchDisplay.runningPackageName
                        scope.launch {
                            if (pkg != null) TaskManager.close(context, pkg)
                            LaunchDisplay.clearRunning()
                        }
                    }
                }
                GameRow.EMULATOR -> CompanionEmulatorChoice(entry)
                GameRow.OVERLAY -> GameButton("$label: ${overlay.label}") {
                    if (PerformanceOverlay.canDraw(context)) PerformanceOverlay.setLevel(context, overlay.next())
                }
                GameRow.PERFORMANCE_MODE -> performanceItem?.let { item ->
                    CompanionCatalogItems(listOf(item), onChanged = { overlayVersion++ })
                }
            }
        }
        // Rows from plugins whose panel declares the game ability (slice C9): RetroArch's save and load, a stream's controls.
        CompanionGamePluginRows(entry)
        // "Save and load from here: Set up" while RetroArch runs it and a step is missing (slice C11).
        CompanionRetroArchSetup(entry, rememberCompanionPanels(pluginMode(LocalCompanionMode.current)))
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
    GameButton("Emulator: ${GameEmulatorChoice.summary(loaded, choice)}") { open = !open }
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

/** A large row button: 56dp, the label saying what it does. */
@Composable
private fun GameButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    CompanionTile(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(if (primary) colors.primary else colors.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (primary) colors.onPrimary else colors.onSurface)
        }
    }
}

/** The question, starting on the safe answer: Keep playing first and focused, the action well apart. */
@Composable
private fun CompanionQuestion(question: CompanionConfirm.Question) {
    val colors = MaterialTheme.colorScheme
    val safe = remember { FocusRequester() }
    LaunchedEffect(question) { runCatching { safe.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(question.text, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(modifier = Modifier.weight(1f).focusRequester(safe)) {
                GameButton("Keep playing", primary = true) { CompanionConfirm.cancel() }
            }
            Column(modifier = Modifier.width(160.dp)) {
                GameButton(question.action) {
                    CompanionConfirm.cancel()
                    question.run()
                }
            }
        }
    }
}

package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.display.secondScreenScroll
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.library.settings.AskFirst
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.library.settings.ControlAccess
import dev.droidtop.library.settings.ControlRow
import dev.droidtop.library.settings.GameControls
import dev.droidtop.library.settings.UiMode
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.library.settings.confirmText
import dev.droidtop.pluginhost.CompanionAbilities
import dev.droidtop.pluginhost.ContextTarget
import dev.droidtop.pluginhost.PluginEpoch
import dev.droidtop.pluginhost.PluginRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Plugins on the companion (docs/SPEC.md "The companion's tabs", docs/plugin-api.md 3 C15, Droidtop/tracker#414 slice
 * C9). A plugin's companion panel is its `ui.panel` (the Quick Menu's panel, [PluginPanels]) drawn for touch with
 * `context.surface` `<mode>.companion`: on the Plugins tab, which lists every panel with its abilities on one line, or
 * as a tab of its own when the person put it on the bar. A panel that declares the `game` ability also gives rows for
 * the running game on the Game tab (`<mode>.companion_game`). Rows a plugin flags `confirm` ask first through
 * [GameControls] (Ask before load and overwrite; always in Kid and Kiosk). Manifests are read off the main thread, and
 * a panel's view is asked for when it shows, never polled.
 */

/** The mode the companion draws for, so a plugin call names its surface (`gaming.companion`). */
internal val LocalCompanionMode = staticCompositionLocalOf { SecondaryDisplayContent.Mode.GAMING }

/** The plugin mode id (`gaming`, `standard`, `desktop`) for a companion mode. */
internal fun pluginMode(mode: SecondaryDisplayContent.Mode): String = mode.name.lowercase()

/** Whether a plugin's row asks first on the companion: a row it flags `confirm`, by [GameControls]' one rule. Pure. */
internal fun pluginRowAsks(item: CatalogItem, mode: UiMode, ask: AskFirst): Boolean =
    GameControls.asksPluginRow(item.confirmText != null, mode, ask)

/** The running entry as a plugin call's `context.game`, the way the Quick Menu's Plugins section hands it over. */
internal fun gameTarget(entry: LibraryEntry): ContextTarget {
    val app = entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP
    return ContextTarget(
        kind = if (app) "app" else "game",
        id = entry.id,
        title = GameNaming.displayName(entry.title),
        systemId = entry.systemId,
        packageName = if (app) entry.id else null,
    )
}

/** The panels for [mode], read from manifests off the main thread; again when a plugin or grant changes. Null while reading. */
@Composable
internal fun rememberCompanionPanels(mode: String): List<PluginPanels.Panel>? {
    val context = LocalContext.current
    val epoch = PluginEpoch.current()
    val panels by produceState<List<PluginPanels.Panel>?>(null, mode, epoch) {
        value = withContext(Dispatchers.IO) {
            runCatching { PluginPanels.panelsFor(context.applicationContext, mode) }.getOrDefault(emptyList())
        }
    }
    return panels
}

/** The running game as a plugin target, or null with nothing running. */
@Composable
private fun rememberRunningTarget(): ContextTarget? {
    val running by LaunchDisplay.running.collectAsState()
    val entries by CompanionState.libraryEntries.collectAsState()
    val id = running?.context?.gameId
    return remember(id, entries) { id?.let { gameId -> entries.firstOrNull { it.id == gameId } }?.let(::gameTarget) }
}

/**
 * The Plugins tab ([only] null): every panel by name with its abilities line; a tap opens it, Back returns. With
 * [only] ("plugin:<id>"), that one panel is the whole tab.
 */
@Composable
internal fun CompanionPluginsTab(only: String?) {
    val mode = pluginMode(LocalCompanionMode.current)
    val panels = rememberCompanionPanels(mode)
    var open by remember(only) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val list = panels
        if (list == null) {
            CompanionNote("Reading…")
            return@Column
        }
        val shownId = only?.removePrefix(CompanionPrefs.PLUGIN_PREFIX) ?: open
        val panel = shownId?.let { id -> list.firstOrNull { it.pluginId == id } }
        if (panel != null) {
            CompanionPluginPanel(panel, onBack = if (only == null) ({ open = null }) else null)
            return@Column
        }
        if (only != null) {
            CompanionNote("Not running")
            return@Column
        }
        Text("Plugins", style = MaterialTheme.typography.titleMedium, color = colors.onBackground, modifier = Modifier.semantics { heading() })
        if (list.isEmpty()) CompanionNote("No plugin has a panel")
        list.forEach { item ->
            CompanionTile(
                onClick = { open = item.pluginId },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(colors.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(item.label, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    item.abilitiesLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * One plugin's panel for touch: its abilities line, its tiles, the view it returns for the companion, and the ways
 * into its other pages; a page a tile or row answered with opens in its place. A panel that declares `keep_on` keeps
 * the companion screen on while it shows.
 */
@Composable
private fun CompanionPluginPanel(panel: PluginPanels.Panel, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val mode = pluginMode(LocalCompanionMode.current)
    val uiMode by UiModeRefresh.mode.collectAsState()
    val settings by CompanionPrefs.settings.collectAsState()
    val game = rememberRunningTarget()
    var reply by remember(panel.pluginId) { mutableStateOf<CatalogScreen?>(null) }
    val screen by produceState<CatalogScreen?>(null, panel.pluginId, game?.id, mode) {
        value = withContext(Dispatchers.IO) {
            PluginPanels.panelScreen(
                context.applicationContext,
                panel,
                PluginPanels.surfaceCompanion(mode),
                game,
                withMore = true,
                onReplyScreen = { reply = it },
            )
        }
    }
    KeepScreenOn(CompanionAbilities.KEEP_ON in panel.abilities)
    val asks: (CatalogItem) -> Boolean = { pluginRowAsks(it, uiMode, settings.ask) }
    val answered = reply
    val shown = screen
    when {
        answered != null -> CompanionNestedScreen(answered, onBack = { reply = null }, onChanged = {}, asks = asks)
        shown != null -> CompanionNestedScreen(shown, onBack = onBack, onChanged = {}, asks = asks, lead = panel.abilitiesLine)
        else -> CompanionNote("Reading…")
    }
}

/**
 * The Game tab's rows from plugins: each running panel that declares the `game` ability, asked with
 * `<mode>.companion_game` and the running game, and only while one of the apps it names in `gamePackages` runs the
 * game (RetroArch's rows for RetroArch). Not in Kid or Kiosk ([ControlRow.GAME_PLUGIN_ROWS]).
 */
@Composable
internal fun CompanionGamePluginRows(entry: LibraryEntry) {
    val context = LocalContext.current
    val mode = pluginMode(LocalCompanionMode.current)
    val uiMode by UiModeRefresh.mode.collectAsState()
    val settings by CompanionPrefs.settings.collectAsState()
    if (!ControlAccess.shows(uiMode, ControlRow.GAME_PLUGIN_ROWS)) return
    val runningPackage = LaunchDisplay.runningPackageName
    val panels = rememberCompanionPanels(mode)?.filter { CompanionAbilities.gameRowsFor(it.entry, runningPackage) } ?: return
    val game = remember(entry.id) { gameTarget(entry) }
    panels.forEach { panel ->
        key(panel.pluginId) {
            val screen by produceState<CatalogScreen?>(null, panel.pluginId, entry.id, mode) {
                value = withContext(Dispatchers.IO) {
                    PluginPanels.panelScreen(context.applicationContext, panel, PluginPanels.surfaceCompanionGame(mode), game, withMore = false)
                }
            }
            screen?.let { CompanionNestedScreen(it, onBack = null, onChanged = {}, asks = { item -> pluginRowAsks(item, uiMode, settings.ask) }) }
        }
    }
}

/** Keeps the companion's window on while [on] (a panel declaring `keep_on`); released when it leaves. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        if (on) view.keepScreenOn = true
        onDispose { if (on) view.keepScreenOn = false }
    }
}

/**
 * "Recording 1:05" on the status line while a plugin that declares the recording ability says it records
 * ([PluginRecording]); spoken once when it starts, with the plugin's name. The timer ticks only while shown, and is not
 * a live region.
 */
@Composable
internal fun CompanionRecordingIndicator() {
    val all by PluginRecording.all.collectAsState()
    val recording = PluginRecording.shown(all) ?: return
    val view = LocalView.current
    LaunchedEffect(recording.pluginId, recording.sinceMs) { view.announceForAccessibility("Recording, by ${recording.label}") }
    val now by produceState(System.currentTimeMillis(), recording.sinceMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        PluginRecording.timer(recording.sinceMs, now),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { contentDescription = "Recording, by ${recording.label}" },
    )
}

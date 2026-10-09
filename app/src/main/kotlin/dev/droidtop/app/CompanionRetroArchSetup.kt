package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConfigFormat
import dev.droidtop.library.consoles.ConfigText
import dev.droidtop.library.consoles.DefaultPlayers
import dev.droidtop.library.consoles.EmulatorSetup
import dev.droidtop.library.consoles.RetroArchCores
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginCatalogPlugin
import dev.droidtop.library.settings.ControlAccess
import dev.droidtop.library.settings.ControlRow
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.pluginhost.CompanionAbilities
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.RiskyPrompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Save and load from here: Set up" on the companion's Game tab (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414
 * slice C11): shown only while RetroArch runs the game and finishing the steps would make the RetroArch manager's Game
 * rows appear, so it never promises what cannot happen. The page has up to two buttons: Get the plugin (installs the
 * RetroArch manager from droidtop's official catalog, then approve it under Plugins) and Turn on (RetroArch's Network
 * Commands, `network_cmd_enable`, written through Emulator setup's one config write path).
 */
internal object RetroArchSetupLine {
    const val PLUGIN_ID = "droidtop.retroarch"
    const val NETWORK_KEY = "network_cmd_enable"

    /** Where the RetroArch manager stands. */
    enum class Plugin { RUNNING, INSTALLED, OFFERED, ABSENT }

    /** What the page offers: the plugin step ([getPlugin] installs it, [approve] says to approve it) and [turnOn]. */
    data class Steps(val getPlugin: Boolean, val approve: Boolean, val turnOn: Boolean)

    /**
     * The steps left, or null when the line is not shown: not RetroArch, nothing missing, or no way to get the plugin (so
     * finishing would not make the rows appear). [networkOn] null: droidtop could not read RetroArch's setting. Pure.
     */
    fun steps(retroArch: Boolean, plugin: Plugin, networkOn: Boolean?): Steps? {
        if (!retroArch || plugin == Plugin.ABSENT) return null
        val turnOn = networkOn != true
        if (plugin == Plugin.RUNNING && !turnOn) return null
        return Steps(getPlugin = plugin == Plugin.OFFERED, approve = plugin == Plugin.INSTALLED, turnOn = turnOn)
    }

    /** RetroArch's config with Network Commands on (the one key; everything else kept). Pure. */
    fun withNetworkCommands(config: String): String = ConfigText.set(config, ConfigFormat.KEY_VALUE, null, NETWORK_KEY, "true")

    fun networkOn(config: String?): Boolean? = config?.let { ConfigText.get(it, ConfigFormat.KEY_VALUE, null, NETWORK_KEY) == "true" }

    /** The official catalog's RetroArch manager with a stable release, from the index last fetched (disk only). */
    fun offered(context: android.content.Context): Pair<PluginCatalogPlugin, dev.droidtop.library.integrations.PluginCatalogRelease>? {
        val official = PluginCatalog.listings(context).firstOrNull { it.source.official } ?: return null
        val origin = official.index.origins.firstOrNull { origin -> origin.plugins.any { it.id == PLUGIN_ID } } ?: return null
        if (PluginCatalog.originState(official.source, origin, emptyMap()) != PluginCatalog.OriginState.Offered) return null
        val plugin = origin.plugins.first { it.id == PLUGIN_ID }
        return PluginCatalog.latestStable(plugin)?.let { plugin to it }
    }
}

@Composable
internal fun CompanionRetroArchSetup(entry: LibraryEntry, panelsRunning: List<dev.droidtop.library.integrations.PluginPanels.Panel>?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiMode by UiModeRefresh.mode.collectAsState()
    if (!ControlAccess.shows(uiMode, ControlRow.GAME_PLUGIN_ROWS)) return
    val pkg = LaunchDisplay.runningPackageName
    var check by remember { mutableIntStateOf(0) }
    val running = panelsRunning?.any { it.pluginId == RetroArchSetupLine.PLUGIN_ID && CompanionAbilities.GAME in it.abilities } == true
    // The plugin's place, the catalog and RetroArch's config: disk (and the helper), so off the main thread.
    val state by produceState<Triple<RetroArchSetupLine.Plugin, Boolean?, EmulatorSetup.Reach>?>(null, pkg, running, check) {
        value = if (pkg == null || !RetroArchCores.isRetroArch(pkg)) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val plugin = when {
                    running -> RetroArchSetupLine.Plugin.RUNNING
                    PluginStore.installed(context).any { it.manifest.id == RetroArchSetupLine.PLUGIN_ID } -> RetroArchSetupLine.Plugin.INSTALLED
                    RetroArchSetupLine.offered(context) != null -> RetroArchSetupLine.Plugin.OFFERED
                    else -> RetroArchSetupLine.Plugin.ABSENT
                }
                val file = DefaultPlayers.retroArchConfigFile(pkg)
                Triple(plugin, RetroArchSetupLine.networkOn(EmulatorSetup.read(file)), EmulatorSetup.writeReach(file))
            }
        }
    }
    val (plugin, networkOn, reach) = state ?: return
    val steps = RetroArchSetupLine.steps(pkg != null && RetroArchCores.isRetroArch(pkg), plugin, networkOn) ?: return
    var open by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Save and load from here", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        CompanionPill(if (open) "Close" else "Set up", selected = !open) { open = !open }
    }
    if (!open) return
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Save and load from here", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        Text(
            "The RetroArch manager plugin adds Save state, Load state, the slot, fast-forward and shaders to this tab. " +
                "It needs RetroArch's Network Commands, on this device only.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
        )
        if (steps.approve) Text("Approve the RetroArch manager under Plugins.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (asking) {
            Text(
                "Turning it on closes RetroArch and starts the game again: play since your last save is lost." +
                    // Through the helper this rewrites a file in RetroArch's folder (a risky action): name it.
                    if (reach == EmulatorSetup.Reach.HELPER) " " + RiskyPrompts.writeFileConfirm("RetroArch", DefaultPlayers.retroArchConfigFile(pkg!!)) else "",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                CompanionPill("Keep playing", selected = true) { asking = false }
                CompanionPill("Turn on") {
                    asking = false
                    scope.launch {
                        status = "Working…"
                        status = turnOnNetworkCommands(context, entry, pkg!!)
                        check++
                    }
                }
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (steps.getPlugin) {
                CompanionPill("Get the plugin", selected = true) {
                    scope.launch {
                        status = "Working…"
                        status = withContext(Dispatchers.IO) {
                            val (found, release) = RetroArchSetupLine.offered(context)
                                ?: return@withContext "The catalog no longer offers it"
                            PluginCatalog.install(context, found, release) { line -> status = line }
                        }
                        check++
                    }
                }
            }
            if (steps.turnOn) CompanionPill("Turn on") { asking = true }
        }
    }
}

/**
 * Turns RetroArch's Network Commands on. RetroArch reads them at start and writes its whole config back when it closes
 * (`config_save_on_exit`), so the game is quit through droidtop's one quit path first, the key written once RetroArch
 * has ended, and the game started again. Says "Done" or why not.
 */
private suspend fun turnOnNetworkCommands(context: android.content.Context, entry: LibraryEntry, pkg: String): String {
    val file = DefaultPlayers.retroArchConfigFile(pkg)
    val reach = withContext(Dispatchers.IO) { EmulatorSetup.writeReach(file) }
    when (reach) {
        EmulatorSetup.Reach.LOCKED -> return "Not turned on. " + RiskyPrompts.turnOnHint(RiskyClass.OTHER_APP_FILES)
        EmulatorSetup.Reach.NONE -> return "Not turned on: droidtop cannot reach RetroArch's settings. In RetroArch: Settings, Network, Network Commands"
        else -> Unit
    }
    CompanionState.onQuitEntry?.invoke(entry) ?: return "Not turned on: droidtop could not quit the game"
    var waited = 0
    while (LaunchDisplay.running.value != null && waited < QUIT_WAIT_MS) {
        delay(250)
        waited += 250
    }
    if (LaunchDisplay.running.value != null) return "Not turned on: RetroArch did not close"
    val written = withContext(Dispatchers.IO) {
        val text = EmulatorSetup.read(file) ?: ""
        EmulatorSetup.write(file, RetroArchSetupLine.withNetworkCommands(text).toByteArray())
    }
    if (!written) return "Not turned on: droidtop could not write RetroArch's settings"
    CompanionState.onLaunchEntry?.invoke(entry)
    return "Done"
}

private const val QUIT_WAIT_MS = 10_000

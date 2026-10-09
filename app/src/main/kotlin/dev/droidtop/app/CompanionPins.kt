package dev.droidtop.app

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ControlAccess
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.PinnedControls
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.StatusIndicators
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.shell.gamepad.QuickPress
import dev.droidtop.shell.gamepad.QuickSection
import dev.droidtop.shell.gamepad.QuickTiles
import dev.droidtop.shell.gamepad.quickSectionGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home's pinned tiles (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): catalog items by id
 * ([PinnedControls]), drawn from the same tile model the Quick Menu's grid uses ([QuickTiles.tile]). A slider pin
 * has a step button on each side. "Edit pins" lists every System, Display and Sound item with Pin or Unpin, so
 * pinning never needs a long-press; Kid and Kiosk get volume and brightness only and no editing. The catalog is built
 * off the main thread while Home shows and after each change.
 */
@Composable
internal fun CompanionPinsSection() {
    val context = LocalContext.current
    val uiMode by UiModeRefresh.mode.collectAsState()
    val state by PinnedControls.pins.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PinnedControls.load(context.applicationContext) } }
    var version by remember { mutableIntStateOf(0) }
    val pinnable by produceState<List<CatalogItem>?>(null, version, uiMode) {
        value = withContext(Dispatchers.IO) {
            val catalog = GamingSettingsCatalog.groups(context)
            listOf(QuickSection.SYSTEM, QuickSection.DISPLAY, QuickSection.AUDIO)
                .flatMap { section -> quickSectionGroups(catalog, section, uiMode).flatMap { it.items } }
                .distinctBy { it.id }
        }
    }
    val items = pinnable ?: return
    val byId = items.associateBy { it.id }
    val shown = PinnedControls.visible(state.pins, uiMode, byId.keys)
    // The clock tile needs the performance sampler: it runs while that pin is on a Home that is on screen.
    if (PinnedControls.needsSampler(shown)) {
        LaunchedEffect(Unit) { dev.droidtop.runtime.systemstatus.PerformanceMonitor.watch(context) }
    }
    var editing by remember { mutableStateOf(false) }
    val canEdit = ControlAccess.shows(uiMode, "", ControlAccess.GROUP_COMPANION)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = when {
                maxWidth >= 600.dp -> 4
                maxWidth >= 360.dp -> 3
                else -> 2
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { id ->
                            val item = byId[id]
                            if (item != null) PinTile(item, Modifier.weight(1f)) { version++ } else StatTile(id, Modifier.weight(1f))
                        }
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (canEdit) {
            Row {
                CompanionPill(if (editing) "Done" else "Edit pins", selected = editing) { editing = !editing }
            }
            if (editing) {
                val choices = items.filter { ControlAccess.pinnable(uiMode, it.id) && it.id !in PinnedControls.STAND_INS.values }
                    .map { it.id to it.title } + PinnedControls.STATS
                choices.forEach { (id, title) ->
                    val pinned = id in state.pins
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        CompanionPill(if (pinned) "Unpin" else "Pin", selected = pinned) {
                            PinnedControls.setPinned(context.applicationContext, id, !pinned)
                        }
                    }
                }
            }
        }
    }
}

/** One pinned control as a tile: the Quick Menu's tile model, pressed the Quick Menu's way ([QuickTiles.pressKind]). */
@Composable
private fun PinTile(item: CatalogItem, modifier: Modifier, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tile = QuickTiles.tile(item)
    val colors = MaterialTheme.colorScheme
    val lit = tile.on == true
    var status by remember(item) { mutableStateOf<String?>(null) }
    if (item is SliderItem) {
        var value by remember(item) { mutableIntStateOf(item.current) }
        val step = QuickTiles.sliderStep(item)
        val percent = if (item.max > item.min) (value - item.min) * 100 / (item.max - item.min) else 0
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(8.dp)
                .semantics(mergeDescendants = true) { stateDescription = "$percent%" },
        ) {
            Text(tile.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                PinStep("−", "Lower ${tile.label.lowercase()}") {
                    value = (value - step).coerceIn(item.min, item.max); item.onChange(context, value)
                }
                Text("$percent%", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                PinStep("+", "Raise ${tile.label.lowercase()}") {
                    value = (value + step).coerceIn(item.min, item.max); item.onChange(context, value)
                }
            }
        }
        return
    }
    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (lit) colors.primaryContainer else colors.surface)
            .clickable(role = if (item is ToggleItem) Role.Switch else Role.Button) {
                when (QuickTiles.pressKind(item)) {
                    QuickPress.TOGGLE -> (item as? ToggleItem)?.let { toggle ->
                        scope.launch { toggle.onToggle(context, !toggle.current); onChanged() }
                    }
                    QuickPress.CYCLE, QuickPress.PICK -> (item as? ChoiceItem)?.let { choice ->
                        val options = choice.options
                        val next = options[(options.indexOfFirst { it.value == choice.current } + 1).mod(options.size)]
                        choice.onSelect(context, next.value)
                        onChanged()
                    }
                    QuickPress.RUN -> (item as? dev.droidtop.library.settings.ActionItem)?.let { it.run(context); onChanged() }
                    QuickPress.RUN_ASYNC -> (item as? dev.droidtop.library.settings.AsyncActionItem)?.let { action ->
                        scope.launch {
                            status = withContext(Dispatchers.IO) { action.run(context) { line -> status = line } }.ifBlank { null }
                            onChanged()
                        }
                    }
                    QuickPress.OPEN -> Unit
                }
            }
            .semantics {
                contentDescription = tile.label
                stateDescription = listOfNotNull(tile.on?.let { if (it) "On" else "Off" }, status ?: tile.value).joinToString(", ")
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tile.label, style = MaterialTheme.typography.labelLarge, color = if (lit) colors.onPrimaryContainer else colors.onSurface, maxLines = 2)
        val value = status ?: tile.value ?: tile.on?.let { if (it) "On" else "Off" }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodySmall, color = if (lit) colors.onPrimaryContainer else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A live stat pin: the battery's heat or draw (from the battery broadcast Home already follows) or the fastest
 * core's clock (from the performance sampler, running only while this tile is on screen). Not a live region: it
 * is read when reached, not on every change.
 */
@Composable
private fun StatTile(id: String, modifier: Modifier) {
    val label = PinnedControls.STATS.firstOrNull { it.first == id }?.second ?: return
    val battery by dev.droidtop.runtime.systemstatus.BatterySampler.latest.collectAsState()
    val history by dev.droidtop.runtime.systemstatus.PerformanceMonitor.history.collectAsState()
    val value = when (id) {
        PinnedControls.STAT_TEMPERATURE -> battery?.tempTenthC?.let { "%.1f °C".format(it / 10.0) }
        PinnedControls.STAT_WATTS -> battery?.watts?.let { "%.1f W".format(it) }
        PinnedControls.STAT_CLOCK -> history.lastOrNull()?.cpuMhz?.let { "$it MHz" }
        else -> null
    } ?: "Not readable"
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .semantics(mergeDescendants = true) {}
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 2)
        Text(value, style = MaterialTheme.typography.titleSmall, color = colors.primary)
    }
}

/**
 * Holds the battery broadcast while the status line is on screen ([dev.droidtop.runtime.systemstatus.BatterySampler]),
 * so Home has the time left and the stat tiles their readings; released when it goes.
 */
@Composable
internal fun rememberBattery(): Pair<dev.droidtop.runtime.systemstatus.BatteryReading?, dev.droidtop.runtime.systemstatus.BatteryEstimate?> {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        dev.droidtop.runtime.systemstatus.BatterySampler.acquire(context)
        onDispose { dev.droidtop.runtime.systemstatus.BatterySampler.release(context) }
    }
    val reading by dev.droidtop.runtime.systemstatus.BatterySampler.latest.collectAsState()
    val estimate by dev.droidtop.runtime.systemstatus.BatterySampler.estimate.collectAsState()
    return reading to estimate
}

/**
 * The optional low-battery line (Companion group "Low battery line", off by default): at or under the chosen level
 * and not charging, a line with Battery saver (Android's screen) and, with the helper app and a game in front, the
 * game's own battery mode.
 */
@Composable
internal fun CompanionLowBatteryLine(reading: dev.droidtop.runtime.systemstatus.BatteryReading?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by dev.droidtop.library.settings.CompanionPrefs.settings.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    val r = reading ?: return
    if (settings.lowBattery <= 0 || r.charging || r.percent > settings.lowBattery) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(note ?: "Battery ${r.percent}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        CompanionPill("Battery saver") {
            dev.droidtop.runtime.systemstatus.SettingsLaunch.start(context, dev.droidtop.runtime.systemstatus.SystemControls.batterySaverSettingsIntent())
        }
        CompanionPill("Game on battery mode") {
            scope.launch {
                note = withContext(Dispatchers.IO) {
                    val game = dev.droidtop.runtime.tasks.LaunchLedger.last?.packageName
                    val provider = runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
                    when {
                        game == null -> "No game in front"
                        !provider -> "Needs the helper app"
                        dev.droidtop.runtime.systemstatus.GameModeControl.set(
                            TaskManager.shell, dev.droidtop.runtime.systemstatus.GameMode.BATTERY, game,
                        ) -> "Game on battery mode"
                        else -> "Android refused"
                    }
                }
            }
        }
    }
}

@Composable
private fun PinStep(label: String, spoken: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The next step the helper-app page offers: get Shizuku, open it to start it, allow droidtop in it, or nothing left. */
internal enum class HelperStep(val button: String?) {
    GET("Get Shizuku"), OPEN("Open Shizuku"), ALLOW("Allow droidtop"), DONE(null);

    companion object {
        /** Pure, so the order is tested. */
        fun next(shizukuInstalled: Boolean, state: BackendState): HelperStep = when (state) {
            BackendState.READY -> DONE
            BackendState.NEEDS_PERMISSION -> ALLOW
            BackendState.ABSENT -> if (shizukuInstalled) OPEN else GET
        }
    }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/**
 * The line Home ends with when no helper app (a `priv.shell` provider) is running, "More controls need the helper
 * app. Set up", and the plain page Set up opens: what the helper adds, that everything else works without it, that the
 * step is fiddly and skipping it loses nothing important, and one button for the next step. Dismissable for good;
 * never in Kid or Kiosk ([PinnedControls.showsProviderLine]).
 */
@Composable
internal fun CompanionProviderLine() {
    val context = LocalContext.current
    val uiMode by UiModeRefresh.mode.collectAsState()
    val pins by PinnedControls.pins.collectAsState()
    var check by remember { mutableIntStateOf(0) }
    // Whether a provider runs, whether the Shizuku app is installed, and where Shizuku stands: binder and package
    // manager calls, so off the main thread, again on each return (the person may have just set it up).
    val helper by produceState<Triple<Boolean, Boolean, BackendState>?>(null, check) {
        value = withContext(Dispatchers.IO) {
            val provider = runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
            val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)
            val state = if (provider) BackendState.READY else dev.droidtop.pluginhost.ElevatedAccessHost.shizukuAppState()
            Triple(provider, installed, state)
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { check++ }
    val (hasProvider, installed, state) = helper ?: return
    if (!PinnedControls.showsProviderLine(hasProvider, uiMode, pins.providerLineDismissed)) return
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("More controls need the helper app.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            CompanionPill(if (open) "Close" else "Set up", selected = !open) { open = !open }
        }
        if (open) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("The helper app", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(
                    "Shizuku lets droidtop switch Wi-Fi, Bluetooth and airplane mode here, put the console to sleep, " +
                        "open the power menu and close apps for good.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
                )
                Text("Everything else works without it.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                Text(
                    "Setting it up is fiddly: Shizuku asks for wireless debugging and a pairing code in its own app. " +
                        "Skipping it loses nothing important.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val step = HelperStep.next(installed, state)
                    step.button?.let { label ->
                        CompanionPill(label, selected = true) {
                            when (step) {
                                HelperStep.GET -> runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                                HelperStep.OPEN -> context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                                    ?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                                HelperStep.ALLOW -> dev.droidtop.pluginhost.ElevatedAccessHost.requestShizukuPermission()
                                HelperStep.DONE -> Unit
                            }
                        }
                    }
                    CompanionPill("Don't show again") { PinnedControls.dismissProviderLine(context.applicationContext) }
                }
            }
        }
    }
}

/**
 * The status line's indicators (docs/SPEC.md "The companion's tabs"): microphone muted, VPN, and "Mic in use" or
 * "Camera in use" while any app records or holds a camera. Read from Android's own callbacks while the line is
 * composed (`AudioManager.registerAudioRecordingCallback`, `CameraManager.AvailabilityCallback`, the
 * microphone-mute broadcast); nothing polls.
 */
@Composable
internal fun rememberStatusIndicators(vpn: Boolean): List<String> {
    val context = LocalContext.current
    var recordings by remember { mutableIntStateOf(0) }
    var cameras by remember { mutableStateOf(emptySet<String>()) }
    var micMuted by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val recordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                recordings = configs?.size ?: 0
            }
        }
        runCatching {
            audio?.registerAudioRecordingCallback(recordingCallback, handler)
            recordings = audio?.activeRecordingConfigurations?.size ?: 0
            micMuted = audio?.isMicrophoneMute == true
        }
        val cameraManager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val cameraCallback = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) { cameras = cameras - cameraId }
            override fun onCameraUnavailable(cameraId: String) { cameras = cameras + cameraId }
        }
        runCatching { cameraManager?.registerAvailabilityCallback(cameraCallback, handler) }
        val muteReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: Intent) {
                micMuted = audio?.isMicrophoneMute == true
            }
        }
        // AudioManager.ACTION_MICROPHONE_MUTE_CHANGED (Android 9); below that it never arrives and the first read stands.
        val filter = android.content.IntentFilter("android.media.action.MICROPHONE_MUTE_CHANGED")
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, muteReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose {
            runCatching { audio?.unregisterAudioRecordingCallback(recordingCallback) }
            runCatching { cameraManager?.unregisterAvailabilityCallback(cameraCallback) }
            runCatching { context.unregisterReceiver(muteReceiver) }
        }
    }
    return StatusIndicators.lines(micMuted, vpn, recordings, cameras.size)
}

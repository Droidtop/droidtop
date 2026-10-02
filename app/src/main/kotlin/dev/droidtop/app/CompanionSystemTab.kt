package dev.droidtop.app

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.runtime.systemstatus.SystemControls
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The companion's System tab (docs/SPEC.md "The companion's tabs"): the controls Android lets an app own
 * (volume, brightness and screen timeout behind the one Modify system settings grant, Do Not Disturb
 * behind its own), the radios (Wi-Fi, Bluetooth, airplane) which only a privileged provider can flip and
 * which otherwise open Android's own switch, and storage and background-job status. All of it is
 * [SystemControls] and the existing job and storage sources: this file is only the touch surface. Where
 * Android would refuse, the line says what is needed.
 */
@Composable
internal fun CompanionSystemTab() {
    CompanionPanels(
        listOf<@Composable () -> Unit>(
            { CompanionCard("Display and sound") { SystemSliders(); ScreenTimeoutChoices() } },
            { CompanionCard("Connections") { RadioRows(); DndPill() } },
            { CompanionCard("Storage") { StorageLine() } },
            { CompanionCard("Downloads and jobs") { JobsLines() } },
        ),
    )
}

/** Whether the Modify system settings grant is held, re-read when the screen comes back from the grant screen. */
@Composable
private fun rememberWriteSettingsGrant(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SystemControls.canWriteBrightness(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = SystemControls.canWriteBrightness(context) }
    return granted
}

/** Volume and brightness: the one pair of sliders, used here and by Standard's second screen. */
@Composable
internal fun SystemSliders() {
    val context = LocalContext.current
    val controls = SystemControls
    var volume by remember { mutableStateOf(controls.volume(context).toFloat()) }
    val volumeMax = remember { controls.volumeRange(context).last.toFloat() }
    var brightness by remember { mutableStateOf((controls.brightness(context) ?: 128).toFloat()) }
    val canBrightness = rememberWriteSettingsGrant()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = volume,
                onValueChange = { volume = it; controls.setVolume(context, it.toInt()) },
                valueRange = 0f..volumeMax,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
        }
        if (canBrightness) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it; controls.setBrightness(context, it.toInt()) },
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
            }
        } else {
            GrantLine("Brightness and screen timeout need the Modify system settings permission.")
        }
    }
}

@Composable
private fun GrantLine(text: String) {
    val context = LocalContext.current
    CompanionNote(text)
    CompanionPill("Allow") { context.startActivity(SystemControls.brightnessGrantIntent(context)) }
}

@Composable
private fun ScreenTimeoutChoices() {
    val context = LocalContext.current
    val canWrite = rememberWriteSettingsGrant()
    var current by remember { mutableStateOf(SystemControls.screenTimeoutMs(context)) }
    CompanionNote("Screen timeout: " + (SystemControls.SCREEN_TIMEOUTS.firstOrNull { it.first == current }?.second ?: current?.let { "${it / 1000} seconds" } ?: "unknown"))
    if (!canWrite) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SystemControls.SCREEN_TIMEOUTS.forEach { (ms, label) ->
            CompanionPill(label, selected = ms == current) {
                if (SystemControls.setScreenTimeoutMs(context, ms)) current = ms
            }
        }
    }
}

/** Do Not Disturb, the one switch an app can own after a one-time grant; used here and by Standard's second screen. */
@Composable
internal fun DndPill() {
    val context = LocalContext.current
    val controls = SystemControls
    var dnd by remember { mutableStateOf(controls.dndEnabled(context)) }
    CompanionPill(if (dnd) "Do Not Disturb on" else "Do Not Disturb off", selected = dnd) {
        if (controls.hasDndAccess(context)) {
            dnd = !dnd
            controls.setDnd(context, dnd)
        } else {
            context.startActivity(controls.dndGrantIntent())
        }
    }
}

/**
 * One row per radio with its state and a switch. With a `priv.shell` provider the switch flips the radio
 * for real; without one it opens Android's own screen for it, and the card says why.
 */
@Composable
private fun RadioRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var shell by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { shell = withContext(Dispatchers.IO) { TaskManager.ops.available().shell } }
    SystemControls.Radio.entries.forEach { radio ->
        val on = remember(tick) { SystemControls.radioOn(context, radio) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                radio.label + ": " + when (on) { true -> "on"; false -> "off"; null -> "unknown" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CompanionPill(if (shell && on != null) (if (on) "Turn off" else "Turn on") else "Open") {
                if (shell && on != null) {
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { TaskManager.ops.exec(SystemControls.radioCommand(radio, !on)) }
                        note = if (out != null && out.exit == 0) null else "${radio.label} could not be switched by the provider."
                        delay(RADIO_SETTLE_MS)
                        tick++
                    }
                } else {
                    context.startActivity(SystemControls.radioPanelIntent(radio))
                }
            }
        }
    }
    CompanionNote(note ?: if (shell) "Switching goes through the Shizuku plugin." else "Switching these on and off needs the Shizuku plugin; without it each opens Android's own switch.")
}

private const val RADIO_SETTLE_MS = 1_200L

@Composable
private fun StorageLine() {
    val storage by produceState<Pair<Long, Long>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val stat = StatFs(Environment.getDataDirectory().path)
                stat.availableBytes to stat.totalBytes
            }.getOrNull()
        }
    }
    val (free, total) = storage ?: run { CompanionNote("Reading storage..."); return }
    Text(storageText(free, total), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    val used = if (total > 0) ((total - free).toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(used)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** "12.3 GB free of 128.0 GB"; pure so it is testable. */
internal fun storageText(freeBytes: Long, totalBytes: Long): String =
    "%.1f GB free of %.1f GB".format(freeBytes / GB, totalBytes / GB)

private const val GB = 1_000_000_000.0

/** The background jobs droidtop is running now (downloads, scrapes, store depots), from the one jobs center. */
@Composable
private fun JobsLines() {
    val jobs by PluginJobsCenter.entries().collectAsState()
    val active = jobs.filter { !it.done }
    if (active.isEmpty()) {
        CompanionNote("No downloads or jobs are running.")
        return
    }
    Text(
        if (active.size == 1) "1 job running" else "${active.size} jobs running",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    active.take(MAX_JOB_LINES).forEach { job ->
        val progress = if (job.percent >= 0) " ${job.percent}%" else ""
        CompanionNote(job.title + ": " + (if (job.paused) "paused" else job.statusLine) + progress)
    }
}

private const val MAX_JOB_LINES = 4


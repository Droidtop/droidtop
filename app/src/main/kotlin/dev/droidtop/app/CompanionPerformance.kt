package dev.droidtop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.droidtop.runtime.systemstatus.AppCpu
import dev.droidtop.runtime.systemstatus.PerfSample
import dev.droidtop.runtime.systemstatus.PerformanceMonitor
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.shell.gamepad.HintTip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The companion's Performance tab (docs/SPEC.md "The companion's tabs"): processor, memory, battery and
 * heat now, each with a graph of the last three minutes from [PerformanceMonitor]'s ring buffer (the one
 * shared sampler; the Quick Menu reads the same history). The sampling loop and the per-app loop run only
 * while this tab is composed AND its window is started (`repeatOnLifecycle`), so a hidden tab or a
 * stopped companion polls nothing. A figure Android does not give a normal app is never drawn from a guess;
 * the card that needs a shell provider (per-app load) is not drawn without one.
 */
@Composable
internal fun CompanionPerformanceTab() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // null: no shell provider, so per-app figures are not permitted.
    var topApps by remember { mutableStateOf<List<AppCpu>?>(null) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { PerformanceMonitor.watch(context) }
            launch {
                while (true) {
                    topApps = withContext(Dispatchers.IO) {
                        val shell = TaskManager.shell
                        if (!shell.capabilities().shellCommand) {
                            null
                        } else {
                            val out = shell.exec(listOf("dumpsys", "cpuinfo"))
                            PerformanceMonitor.parseTopApps(out?.takeIf { it.exit == 0 }?.stdout)
                        }
                    }
                    delay(TOP_APPS_INTERVAL_MS)
                }
            }
        }
    }

    val history by PerformanceMonitor.history.collectAsState()
    val latest = history.lastOrNull()
    if (latest == null) {
        CompanionPanels(listOf<@Composable () -> Unit>({ CompanionCard("Performance") { CompanionNote("Reading the device...") } }))
        return
    }
    val colors = MaterialTheme.colorScheme
    CompanionPanels(
        listOf<@Composable () -> Unit>(
            {
                CompanionCard("Processor") {
                    val device = history.any { it.deviceCpuPercent != null }
                    // The headline is a value: the whole-device load when Android gives it, else droidtop's
                    // own share, else the clock. What Android hides is a small note, never the headline.
                    Text(
                        latest.deviceCpuPercent?.let { "$it% in use" }
                            ?: latest.ownCpuPercent?.let { "droidtop: $it% of all cores" }
                            ?: latest.cpuMhz?.let { "Fastest core: $it MHz" }
                            ?: "Reading the processor...",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Sparkline(
                        history.map { (if (device) it.deviceCpuPercent else it.ownCpuPercent)?.toFloat() },
                        0f, 100f, colors.primary,
                    )
                    if (latest.deviceCpuPercent != null) {
                        CompanionNote("droidtop: ${latest.ownCpuPercent ?: 0}%")
                    }
                    if (latest.deviceCpuPercent != null || latest.ownCpuPercent != null) {
                        latest.cpuMhz?.let { CompanionNote("Fastest core: $it MHz") }
                    }
                }
            },
            {
                CompanionCard("Memory") {
                    Text("${latest.memUsedPercent}% used", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Sparkline(history.map { it.memUsedPercent.toFloat() }, 0f, 100f, colors.secondary)
                    CompanionNote("${latest.memAvailMb} MB free of ${latest.memTotalMb} MB" + if (latest.lowMemory) ", low memory" else "")
                }
            },
            {
                CompanionCard("Battery") {
                    Text(batteryLine(latest), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Sparkline(history.map { it.batteryPercent?.toFloat() }, 0f, 100f, colors.tertiary)
                    latest.batteryMilliamps?.let { CompanionNote("$it mA") }
                }
            },
            {
                CompanionCard("Heat") {
                    HintTip("Battery sensor. Android gives apps no processor or GPU temperature.") {
                        Text(PerformanceMonitor.tempText(latest.batteryTempTenthC), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    }
                    Sparkline(history.map { it.batteryTempTenthC?.let { t -> t / 10f } }, 20f, 60f, colors.error)
                    CompanionNote("Thermal state: ${PerformanceMonitor.thermalLabel(latest.thermalStatus)}")
                }
            },
            { CompanionLogsCard() },
            {
                // Per-app figures exist only with a shell provider (Shizuku): without one the card is not drawn.
                val apps = topApps
                if (apps != null) {
                    CompanionCard("Apps") {
                        if (apps.isEmpty()) {
                            CompanionNote("None")
                        } else {
                            apps.forEach { CompanionNote("%.1f%%  %s".format(it.percent, it.packageName)) }
                        }
                    }
                }
            },
        ),
    )
}

private const val TOP_APPS_INTERVAL_MS = 6_000L

private fun batteryLine(sample: PerfSample): String {
    val level = sample.batteryPercent?.let { "$it%" } ?: "Unknown"
    return level + if (sample.charging) ", charging" else ", on battery"
}


/**
 * A line graph of [values] over the buffer's whole window, newest at the right; a missing reading breaks
 * the line. Until two readings exist the box says it is collecting them, so it never reads as a broken
 * empty rectangle.
 */
@Composable
private fun Sparkline(values: List<Float?>, min: Float, max: Float, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val collecting = values.count { it != null } < 2
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(track),
        contentAlignment = Alignment.Center,
    ) {
        if (collecting) {
            Text("Collecting readings...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (collecting || max <= min) return@Canvas
            val step = size.width / (PerformanceMonitor.CAPACITY - 1)
            val path = Path()
            var drawing = false
            values.forEachIndexed { index, value ->
                if (value == null) {
                    drawing = false
                } else {
                    val x = size.width - (values.size - 1 - index) * step
                    val y = size.height - ((value.coerceIn(min, max) - min) / (max - min)) * size.height
                    if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                    drawing = true
                }
            }
            drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

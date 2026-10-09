package dev.droidtop.app

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.systemstatus.PowerProbe
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android's battery health constant in words. Pure. */
internal fun batteryHealth(health: Int): String? = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Worn out"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
    BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failing"
    else -> null
}

/** One cluster's line: "CPU 0-3: 1804 MHz, schedutil". Pure. */
internal fun clusterLine(cluster: PowerProbe.Cluster): String =
    "CPU ${cluster.cpus?.split(' ')?.let { if (it.size > 1) "${it.first()}-${it.last()}" else it.first() } ?: cluster.policy}: " +
        "${(cluster.curKHz ?: 0) / 1000} MHz" + (cluster.governor?.let { ", $it" } ?: "")

/**
 * System > Power (docs/SPEC.md "The companion's tabs", Power and performance; slice C16): the battery's health,
 * temperature and charge rate, Battery saver (Android's screen), the fan mode and charge limit where [PowerProbe]
 * finds them as device settings (shown with their values; the device's own page or Android's opens to change them),
 * each cluster's clock and the GPU's where readable, and with Root-level commands a cluster's governor and highest
 * clock. All reads off the main thread, once when the card opens and after a change.
 */
@Composable
internal fun CompanionPowerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val battery by dev.droidtop.runtime.systemstatus.BatterySampler.latest.collectAsState()
    val health by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.let(::batteryHealth)
        }
    }
    val visible by produceState<PowerProbe.Visible?>(null, version) {
        value = withContext(Dispatchers.IO) {
            PowerProbe.visible(PowerProbe.probe(), PowerProbe.clusters(), PowerProbe.gpuMHz(), PowerProbe.rootTuning())
        }
    }
    val status = remember { mutableStateMapOf<String, String>() }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val facts = listOfNotNull(
            health?.let { "Health $it" },
            battery?.tempTenthC?.let { "%.1f °C".format(it / 10.0) },
            battery?.watts?.let { "%.1f W".format(it) + if (battery?.charging == true) " charging" else " draw" },
        )
        if (facts.isNotEmpty()) Text("Battery: " + facts.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompanionPill("Battery saver") {
                SettingsLaunch.start(context, dev.droidtop.runtime.systemstatus.SystemControls.batterySaverSettingsIntent())
            }
        }
        val v = visible ?: run { CompanionNote("Reading…"); return@Column }
        if (v.fan.isNotEmpty() || v.chargeLimit.isNotEmpty()) {
            v.fan.forEach { (key, value) -> Text("Fan (${key.substringAfter('/')}): $value", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface) }
            v.chargeLimit.forEach { (key, value) -> Text("Charge limit (${key.substringAfter('/')}): $value", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface) }
            CompanionNote("Change them in the device's own settings")
            CompanionPill("Open battery settings") {
                val page = PowerProbe.pageFor(emptyList(), Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) { it.resolveActivity(context.packageManager) != null }
                    ?: Intent(Settings.ACTION_SETTINGS)
                SettingsLaunch.start(context, page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        v.clusters.forEach { cluster ->
            Text(clusterLine(cluster), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            if (v.tuning && cluster.governors.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    cluster.governors.forEach { gov ->
                        CompanionPill(gov, selected = gov == cluster.governor) {
                            scope.launch {
                                status[cluster.policy] = withContext(Dispatchers.IO) { PowerProbe.write(cluster.policy, "scaling_governor", gov) } ?: "Governor $gov"
                                version++
                            }
                        }
                    }
                }
                if (cluster.frequencies.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        cluster.frequencies.sortedDescending().forEach { khz ->
                            CompanionPill("Up to ${khz / 1000} MHz", selected = khz == cluster.maxKHz) {
                                scope.launch {
                                    status[cluster.policy] = withContext(Dispatchers.IO) { PowerProbe.write(cluster.policy, "scaling_max_freq", khz.toString()) } ?: "Up to ${khz / 1000} MHz"
                                    version++
                                }
                            }
                        }
                    }
                }
            }
            status[cluster.policy]?.let { CompanionNote(it) }
        }
        v.gpuMHz?.let { Text("GPU: $it MHz", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface) }
    }
}

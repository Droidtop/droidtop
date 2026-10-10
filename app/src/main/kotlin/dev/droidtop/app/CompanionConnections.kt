package dev.droidtop.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import dev.droidtop.runtime.systemstatus.VolumeControl
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * System > Sound's own rows (docs/SPEC.md "The companion's tabs", Sound and connections; Droidtop/tracker#414 slice
 * C19): a slider for each stream, every one through [VolumeControl], the one volume write path where Kid's cap holds,
 * and Android's output and volume panel.
 */
@Composable
internal fun CompanionStreamVolumes() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VolumeControl.Stream.entries.forEach { stream -> StreamSlider(stream) }
        CompanionPill("Outputs") {
            // Android's own volume and output panel (Android 10 and later), else its sound settings.
            val panel = Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
            SettingsLaunch.start(context, panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun StreamSlider(stream: VolumeControl.Stream) {
    val context = LocalContext.current
    val max = remember(stream) { VolumeControl.max(context, stream) }
    var value by remember(stream) { mutableIntStateOf(VolumeControl.get(context, stream)) }
    fun set(next: Int) {
        VolumeControl.set(context, stream, next)
        value = VolumeControl.get(context, stream)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stream.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(0.3f))
        CompanionPill("−") { set(value - 1) }
        Slider(
            value = value.toFloat(),
            onValueChange = { set(it.toInt()) },
            valueRange = 0f..max.toFloat().coerceAtLeast(1f),
            modifier = Modifier.weight(0.7f).semantics { contentDescription = "${stream.label} volume"; stateDescription = "${value * 100 / max.coerceAtLeast(1)}%" },
        )
        CompanionPill("+") { set(value + 1) }
    }
}

/** One network fact line for the Connections card. */
internal data class NetworkDetail(
    val kind: String,
    val ip: List<String>,
    val vpn: Boolean,
    val signalLevel: Int?,
    val bandGhz: String?,
    val linkMbps: Int?,
    val name: String?,
    val adbWifi: Boolean?,
)

/** "5 GHz" or "2.4 GHz" (or "6 GHz") from a Wi-Fi frequency in MHz. Pure. */
internal fun band(frequencyMhz: Int): String? = when (frequencyMhz) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..5900 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    else -> null
}

/** The lines the Connections card shows for [detail]. Pure. */
internal fun networkLines(detail: NetworkDetail): List<String> = buildList {
    add(listOfNotNull(detail.kind, detail.name).joinToString(": "))
    if (detail.ip.isNotEmpty()) add("IP " + detail.ip.joinToString(", "))
    listOfNotNull(
        detail.signalLevel?.let { "Signal $it of 4" },
        detail.bandGhz,
        detail.linkMbps?.let { "$it Mbps" },
    ).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
    if (detail.vpn) add("VPN on")
    detail.adbWifi?.let { add(if (it) "ADB over Wi-Fi: on" else "ADB over Wi-Fi: off") }
}

/**
 * System > Connections (slice C19): the network in detail (address, signal, band, link speed, VPN; with the helper app
 * also the network's name, which Android otherwise keeps behind location, and whether ADB over Wi-Fi is on), and the
 * Bluetooth devices paired with the device, asking for Bluetooth access when the card is first opened (owner question
 * 1's default). Read off the main thread when the card opens.
 */
@Composable
internal fun CompanionConnections() {
    val context = LocalContext.current
    val view = LocalView.current
    var version by remember { mutableIntStateOf(0) }
    val detail by produceState<NetworkDetail?>(null, version) { value = withContext(Dispatchers.IO) { readNetwork(context.applicationContext) } }
    val devices by produceState<List<String>?>(null, version) { value = withContext(Dispatchers.IO) { bondedDevices(context.applicationContext) } }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        detail?.let { d -> networkLines(d).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface) } }
            ?: CompanionNote("Reading…")
        Text("Bluetooth devices", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        val list = devices
        when {
            list == null -> {
                CompanionNote("Bluetooth access lets droidtop list your devices")
                CompanionPill("Allow") {
                    val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
                    if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        activity.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41)
                    } else {
                        SettingsLaunch.start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    version++
                }
            }
            list.isEmpty() -> CompanionNote("None paired")
            else -> list.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface) }
        }
        CompanionPill("Bluetooth settings") { SettingsLaunch.start(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Paired Bluetooth devices by name, or null while droidtop lacks Bluetooth access (Android 12 and later). */
@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<String>? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) return null
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching { adapter.bondedDevices.map { it.name ?: it.address } }.getOrDefault(emptyList())
}

@Suppress("DEPRECATION")
private fun readNetwork(context: Context): NetworkDetail {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm?.activeNetwork
    val caps = network?.let { cm.getNetworkCapabilities(it) }
    val link = network?.let { cm.getLinkProperties(it) }
    val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val kind = when {
        caps == null -> "Offline"
        wifi -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        else -> "Connected"
    }
    val info = if (wifi) context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo else null
    val shell = TaskManager.shell
    val provider = runCatching { shell.capabilities().shellCommand }.getOrDefault(false)
    // The network's name: Android shows it to apps only with location; the helper app reads it from `cmd wifi status`.
    val name = if (wifi && provider) {
        shell.exec(listOf("cmd", "wifi", "status"))?.stdout?.let { Regex("SSID: \"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    } else {
        null
    }
    val adbWifi = if (provider) {
        shell.exec(listOf("settings", "get", "global", "adb_wifi_enabled"))?.stdout?.trim()?.let { it == "1" }
    } else {
        null
    }
    return NetworkDetail(
        kind = kind,
        ip = link?.linkAddresses?.map { it.address.hostAddress.orEmpty() }?.filter { it.isNotBlank() && !it.startsWith("fe80") }.orEmpty(),
        vpn = cm?.allNetworks?.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } == true,
        signalLevel = info?.let { WifiManager.calculateSignalLevel(it.rssi, 5) },
        bandGhz = info?.frequency?.let(::band),
        linkMbps = info?.linkSpeed?.takeIf { it > 0 },
        name = name,
        adbWifi = adbWifi,
    )
}

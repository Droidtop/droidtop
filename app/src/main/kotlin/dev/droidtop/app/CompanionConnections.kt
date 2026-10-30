package dev.droidtop.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import dev.droidtop.runtime.systemstatus.VolumeControl
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

/** One stream's volume: the one slider row, through [VolumeControl], where Kid's cap holds; read off the main thread. */
@Composable
private fun StreamSlider(stream: VolumeControl.Stream) {
    val context = LocalContext.current
    val app = context.applicationContext
    val read by produceState<Pair<Int, Int>?>(null, stream) {
        value = withContext(Dispatchers.IO) { VolumeControl.get(app, stream) to VolumeControl.max(app, stream) }
    }
    val (current, max) = read ?: return
    CompanionSliderRow(
        title = stream.label,
        value = current.toFloat(),
        range = 0f..max.toFloat().coerceAtLeast(1f),
        step = 1f,
        writeKey = "volume:${stream.name}",
        write = { next -> VolumeControl.set(app, stream, kotlin.math.round(next).toInt()) },
    )
}

/**
 * System > Connections (slice C19): the Bluetooth devices paired with the device, asking for Bluetooth access when the
 * card is first opened (owner question 1's default), and the way to the Network tab, where each network is shown in
 * detail (Droidtop/tracker#583). Read off the main thread when the card opens.
 */
@Composable
internal fun CompanionConnections() {
    val context = LocalContext.current
    val view = LocalView.current
    var version by remember { mutableIntStateOf(0) }
    val devices by produceState<List<String>?>(null, version) { value = withContext(Dispatchers.IO) { bondedDevices(context.applicationContext) } }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LocalCompanionNav.current?.let { nav -> CompanionPill("Network") { nav.openTab(CompanionTab.NETWORK) } }
        Text(stringResource(R.string.companion_connections_bluetooth_devices), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
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

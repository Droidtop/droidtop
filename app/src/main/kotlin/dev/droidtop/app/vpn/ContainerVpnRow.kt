package dev.droidtop.app.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.ContainerLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "VPN" row on a container's entry in the container manager
 * (docs/SPEC.md 4a): the socket the container is expected to serve, a
 * switch that makes it the device's VPN, the live state, the apps it
 * carries, and a link to Android's own always-on settings.
 */
@Composable
fun ContainerVpnRow(containerId: String, hostSocketDir: File?, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DroidtopVpnService.state.collectAsState()
    var config by remember { mutableStateOf<VpnPrefs.Config?>(null) }
    var choosingApps by remember { mutableStateOf(false) }
    LaunchedEffect(state) { config = withContext(Dispatchers.IO) { VpnPrefs.read(context) } }

    val socket = hostSocketDir?.let { File(it, ContainerLayout.VPN_SOCKET) }
    val servesVpn = config?.containerId == containerId && state != VpnState.Off

    fun turnOn() {
        socket ?: return
        scope.launch {
            withContext(Dispatchers.IO) { VpnPrefs.serveFrom(context, containerId, socket) }
            DroidtopVpnService.start(context)
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) turnOn()
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("VPN", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (servesVpn) describe(state) else "Carries the device's traffic through a SOCKS5 proxy at ${ContainerLayout.SOCKET_DIR}/${ContainerLayout.VPN_SOCKET}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (servesVpn && state != VpnState.Connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = servesVpn,
                enabled = enabled && socket != null,
                onCheckedChange = { on ->
                    if (!on) {
                        DroidtopVpnService.stop(context)
                    } else {
                        // Another container's VPN is replaced, not stacked.
                        DroidtopVpnService.stop(context)
                        val ask = VpnService.prepare(context)
                        if (ask != null) consent.launch(ask) else turnOn()
                    }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val apps = config?.allowedApps.orEmpty()
            Text(
                if (apps.isEmpty()) "Routes every app" else "Routes ${apps.size} app${if (apps.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { choosingApps = !choosingApps }, enabled = enabled) {
                Text(if (choosingApps) "Done" else "Choose apps")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }) {
                Text("Always-on in Android")
            }
        }
        if (choosingApps) {
            AppChooser(
                selected = config?.allowedApps.orEmpty(),
                onChange = { apps ->
                    scope.launch {
                        withContext(Dispatchers.IO) { VpnPrefs.setAllowedApps(context, apps) }
                        config = config?.copy(allowedApps = apps)
                        // The app list is fixed when the tunnel is made.
                        if (servesVpn) {
                            DroidtopVpnService.stop(context)
                            DroidtopVpnService.start(context)
                        }
                    }
                },
            )
        }
    }
}

private fun describe(state: VpnState): String = when (state) {
    VpnState.Connected -> "Connected: the device's traffic goes through this container"
    VpnState.NoEndpoint -> "Nothing is serving the VPN socket, so traffic is held back. Start the container's VPN client, or turn this off"
    is VpnState.Failed -> "Couldn't start: ${state.message}"
    VpnState.Off -> "Off"
}

private data class LaunchableApp(val packageName: String, val label: String)

/**
 * "Route only these apps": the launchable apps, each ticked or not. None
 * ticked means every app. droidtop itself is never offered, because its
 * container's own traffic must stay outside the tunnel it serves.
 */
@Composable
private fun AppChooser(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Tick the apps to route. With none ticked, every app is routed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = apps
        if (list == null) {
            Text("Loading apps…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            list.forEach { app ->
                val ticked = app.packageName in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onChange(if (ticked) selected - app.packageName else selected + app.packageName) },
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null)
                    Text(app.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { (pkg, _) -> pkg != context.packageName }
        .distinctBy { it.first }
        .map { (pkg, label) -> LaunchableApp(pkg, label) }
        .sortedBy { it.label.lowercase() }
}

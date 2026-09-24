package dev.droidtop.app

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class UsbNode(val path: String, val label: String)

/**
 * The "Devices" row on a container's entry (docs/SPEC.md 4b): the USB
 * devices Android sees now, each bound into the container at its own
 * node path when ticked, from the container's next start. A backend that
 * cannot share a node (proot) says why and offers nothing to tick.
 */
@Composable
internal fun ContainerDevicesRow(runtime: ContainerRuntime?, container: Container, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailable = runtime?.deviceSharingUnavailableReason
    var devices by remember { mutableStateOf<List<UsbNode>?>(null) }
    var shared by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(runtime, container.id) {
        if (runtime == null || unavailable != null) return@LaunchedEffect
        devices = withContext(Dispatchers.IO) { usbNodes(context) }
        shared = runCatching { runtime.sharedDevices(container).toSet() }.getOrDefault(emptySet())
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Devices", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        val list = devices
        when {
            runtime == null -> Unit
            unavailable != null -> Supporting(unavailable)
            list == null -> Supporting("Looking for USB devices…")
            list.isEmpty() && shared.isEmpty() -> Supporting("No USB device is connected. Plug one in, then open Containers again.")
            else -> {
                Supporting("Ticked devices are available in this container from its next start.")
                // A device ticked earlier and unplugged now is still listed,
                // so it can be unticked.
                val rows = list + shared.filter { path -> list.none { it.path == path } }.map { UsbNode(it, "Not connected") }
                rows.forEach { node ->
                    val ticked = node.path in shared
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = enabled) {
                                val next = if (ticked) shared - node.path else shared + node.path
                                shared = next
                                scope.launch { runCatching { runtime.setSharedDevices(container, next.sorted()) } }
                            },
                    ) {
                        Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled)
                        Column(Modifier.weight(1f)) {
                            Text(node.label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(node.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Supporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What Android enumerates; listing needs no permission, and each device's name is its node path. */
private fun usbNodes(context: Context): List<UsbNode> {
    val usb = context.getSystemService(UsbManager::class.java) ?: return emptyList()
    return usb.deviceList.values.map { device ->
        val label = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { "USB device" }
        UsbNode(device.deviceName, label)
    }.sortedBy { it.path }
}

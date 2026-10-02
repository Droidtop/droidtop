package dev.droidtop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.TaskPolicy
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.SharedRunningAppsList
import kotlinx.coroutines.launch

/** Touch-only companion view of the shared running-app list. */
@Composable
internal fun CompanionTasks() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by TaskManager.snapshot.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<RunningApp>?>(null) }
    LaunchedEffect(Unit) { TaskManager.watch(context) }
    val displays = remember { TaskManager.displayIds(context) }
    val apps = snapshot?.apps.orEmpty()

    fun clearAll(targets: List<RunningApp>) {
        pending = null
        detail = "Closing ${targets.size} apps…"
        scope.launch { detail = TaskManager.clearAll(context, targets).message }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (snapshot == null) {
            Text("Reading apps…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (apps.isEmpty()) {
            Text("No apps running", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SharedRunningAppsList(
                apps = apps,
                displays = displays,
                onClear = {
                    scope.launch {
                        val targets = TaskManager.clearAllTargets(context)
                        when {
                            targets.isEmpty() -> detail = "No apps to close"
                            TaskPolicy.needsClearAllConfirm(targets.size) -> pending = targets
                            else -> clearAll(targets)
                        }
                    }
                },
                onSwitch = { _, app -> detail = TaskActions.bringTo(context, app.packageName, app.displayId) },
                onClose = { _, app ->
                    detail = "Closing ${app.label}…"
                    scope.launch { detail = "${app.label}: ${TaskManager.close(context, app.packageName).text}" }
                },
                onMove = { _, app ->
                    val other = displays.firstOrNull { it != app.displayId }
                    if (other != null) detail = TaskActions.bringTo(context, app.packageName, other)
                        ?: "Asked for ${app.label} on the other screen"
                },
            )
        }
        pending?.let { targets ->
            Text("Close ${targets.size} apps?", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.TextButton(onClick = { clearAll(targets) }) { Text("Close") }
                androidx.compose.material3.TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        }
        (detail ?: if (snapshot?.note != null) "Limited app list" else null)?.let { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        snapshot?.note?.let { note ->
            androidx.compose.material3.TextButton(onClick = { showDetails = !showDetails }) { Text("Info") }
            if (showDetails) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

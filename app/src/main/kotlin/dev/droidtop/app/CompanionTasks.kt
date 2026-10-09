package dev.droidtop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.tasks.CloseOutcome
import dev.droidtop.runtime.tasks.ProtectedApps
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.SharedRunningAppsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Touch-only companion view of the shared running-app list (docs/SPEC.md "The companion's tabs", Apps). Clear all
 * asks first, with the safe answer first, and leaves alone what is protected and whatever is open on this
 * companion's own screen; each row's Protect keeps its app from Clear all (Droidtop/tracker#252).
 */
@Composable
internal fun CompanionTasks() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snapshot by TaskManager.snapshot.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }
    // Per-row state in the row's own subtitle: "Closing…", then the row goes, or a short result stays.
    val rowStates = remember { mutableStateMapOf<String, String>() }
    var pending by remember { mutableStateOf<List<RunningApp>?>(null) }
    var protectedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { protectedApps = withContext(Dispatchers.IO) { ProtectedApps.get(context) } }
    LaunchedEffect(Unit) { TaskManager.watch(context) }
    val displays = remember { TaskManager.displayIds(context) }
    val apps = snapshot?.apps.orEmpty()

    fun clearAll(targets: List<RunningApp>) {
        pending = null
        detail = "Closing…"
        scope.launch { detail = TaskManager.clearAll(context, targets).message }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (snapshot == null) {
            Text("Reading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (apps.isEmpty()) {
            Text("Nothing running", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SharedRunningAppsList(
                apps = apps,
                displays = displays,
                rowMessage = { i -> apps.getOrNull(i)?.let { rowStates[it.packageName] } },
                protectedPackages = protectedApps,
                onProtect = { _, app ->
                    val protect = app.packageName !in protectedApps
                    protectedApps = if (protect) protectedApps + app.packageName else protectedApps - app.packageName
                    scope.launch(Dispatchers.IO) { ProtectedApps.set(context, app.packageName, protect) }
                },
                onClear = {
                    // The companion's own screen keeps what was opened on it.
                    val own = setOfNotNull(view.display?.displayId)
                    scope.launch {
                        val targets = TaskManager.clearAllTargets(context, keepDisplays = own)
                        if (targets.isEmpty()) detail = "Nothing to close" else pending = targets
                    }
                },
                onSwitch = { _, app -> detail = TaskActions.bringTo(context, app.packageName, app.displayId) },
                onClose = { _, app ->
                    rowStates[app.packageName] = "Closing…"
                    scope.launch {
                        val outcome = TaskManager.close(context, app.packageName)
                        if (outcome is CloseOutcome.Closed) rowStates.remove(app.packageName) else rowStates[app.packageName] = outcome.text
                    }
                },
                onMove = { _, app ->
                    val other = displays.firstOrNull { it != app.displayId }
                    if (other != null) detail = TaskActions.bringTo(context, app.packageName, other)
                },
            )
        }
        pending?.let { targets ->
            Text(
                if (targets.size == 1) "Close ${targets.first().label}?" else "Close ${targets.size} apps?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            // The safe answer first, the two well apart.
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(top = 8.dp)) {
                CompanionPill("Keep them", selected = true) { pending = null }
                CompanionPill("Close") { clearAll(targets) }
            }
        }
        (detail ?: if (snapshot?.note != null) "Limited list" else null)?.let { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

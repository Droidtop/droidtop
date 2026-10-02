package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.TaskPolicy
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.AppIcon
import kotlinx.coroutines.launch

/**
 * The companion's running apps, touch only (docs/SPEC.md "The task manager", Droidtop/tracker#252, #186):
 * a row of what is open with the app's icon, a tap to switch to it, an arrow pair to ask for it on the other screen, a cross to close it, and Clear all
 * apps in front. Nothing here takes focus or answers a controller; the controller keeps driving the
 * shell on the other screen.
 *
 * The list is [TaskManager.snapshot], the one shared source. It is read only while this composable is
 * on screen ([TaskManager.watch] in a `LaunchedEffect`), so a companion that is not showing polls
 * nothing. Switching to an app launches it on the screen it is on, never on the companion's own screen
 * (#243: the companion does not move what the user opened). Clear all asks first when it would close
 * more than a few apps, inline.
 */
@Composable
internal fun CompanionTasks() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by TaskManager.snapshot.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<List<RunningApp>?>(null) }
    LaunchedEffect(Unit) { TaskManager.watch(context) }
    // The other screen, when there is one: where Move sends an app. Read once, a display manager call.
    val displays = remember { TaskManager.displayIds(context) }

    val apps = snapshot?.apps.orEmpty()
    // Nothing running and nothing to say: no row at all, so an idle companion stays as it was.
    if (apps.isEmpty() && message == null) return

    fun clearAll(targets: List<RunningApp>) {
        pending = null
        message = "Closing ${targets.size} apps..."
        scope.launch { message = TaskManager.clearAll(context, targets).message }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Running apps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            TaskPill("Clear all apps") {
                scope.launch {
                    val targets = TaskManager.clearAllTargets(context)
                    when {
                        targets.isEmpty() -> message = "Nothing to close."
                        TaskPolicy.needsClearAllConfirm(targets.size) -> pending = targets
                        else -> clearAll(targets)
                    }
                }
            }
        }
        pending?.let { targets ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Close ${targets.size} apps?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TaskPill("Close them") { clearAll(targets) }
                TaskPill("Cancel") { pending = null }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items(apps, key = { it.packageName + it.displayId }) { app ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .heightIn(min = 48.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                message = TaskActions.bringTo(context, app.packageName, app.displayId)
                            }
                            .heightIn(min = 48.dp)
                            .padding(start = 8.dp, end = 4.dp),
                    ) {
                        AppIcon(app.packageName)
                        Text(
                            app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                        )
                    }
                    val other = displays.firstOrNull { it != app.displayId }
                    if (other != null) {
                        // Asks for the app on the other screen; whether Android moves the task is its call (SPEC "The task manager").
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable {
                                    message = TaskActions.bringTo(context, app.packageName, other)
                                        ?: "Asked for ${app.label} on the other screen."
                                }
                                .padding(horizontal = 12.dp),
                        ) {
                            Text("⇄", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable {
                                message = "Closing ${app.label}..."
                                scope.launch { message = "${app.label}: ${TaskManager.close(context, app.packageName).text}" }
                            }
                            .padding(horizontal = 14.dp),
                    ) {
                        Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // The task manager's own honest note, and what the last action did; a tap dismisses.
        val line = message ?: snapshot?.note
        if (line != null) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { message = null }
                    .padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun TaskPill(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

package dev.droidtop.shell.gamepad

import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskPolicy
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import kotlinx.coroutines.launch

/**
 * The Quick Menu's Apps tab: what is running, across both screens, with the three things a task manager
 * does (docs/SPEC.md "The task manager", Droidtop/tracker#252). The list is [TaskManager.snapshot], the one
 * shared source the companion and Standard's home read too; it is refreshed only while this tab is
 * composed ([TaskManager.watch]), so nothing polls with the sheet closed or another tab showing.
 *
 * The first row is Clear all apps (tracker#252), the same [TaskManager.clearAll] the System tab, the
 * companion and Standard's home run: with more than a few to close it takes a second A press, and says
 * how many first. Below it, A switches to the app on the screen it is on, X closes it, Y moves it to the
 * other screen when there is one. Which of those Android allows is the task manager's to say: a close that cannot be confirmed
 * reads as such in the row, never as nothing.
 */
@Composable
internal fun AppsTab(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by TaskManager.snapshot.collectAsState()
    val apps = snapshot?.apps.orEmpty()
    var focusIndex by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    // Clear all, waiting for its second press: set when it found more than a few apps to close.
    var clearAllArmed by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val press = rememberGamepadTouch()
    // Row 0 is Clear all apps, then one row per app.
    val index = focusIndex.coerceIn(0, apps.size)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(Unit) { TaskManager.watch(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPad { press ->
                val app = apps.getOrNull(index - 1)
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        focusIndex = menuStep(index, apps.size + 1, if (press.action == GamepadAction.UP) -1 else 1)
                        clearAllArmed = false
                        message = null
                    }
                    GamepadAction.B -> onDismiss()
                    GamepadAction.A -> if (index == 0) {
                        scope.launch {
                            val targets = TaskManager.clearAllTargets(context)
                            if (TaskPolicy.needsClearAllConfirm(targets.size) && !clearAllArmed) {
                                clearAllArmed = true
                                message = "Press A again to close ${targets.size} apps"
                            } else {
                                clearAllArmed = false
                                message = "Closing ${targets.size} apps..."
                                message = TaskManager.clearAll(context, targets).message
                            }
                        }
                    } else if (app != null) {
                        val failure = TaskActions.bringTo(context, app.packageName, app.displayId)
                        if (failure == null) onDismiss() else message = failure
                    }
                    GamepadAction.X -> if (app != null) {
                        message = "Closing ${app.label}..."
                        scope.launch {
                            val outcome = TaskManager.close(context, app.packageName)
                            message = "${app.label}: ${outcome.text}"
                            TaskManager.refresh(context)
                        }
                    }
                    GamepadAction.Y -> if (app != null) {
                        val target = TaskPolicy.otherDisplay(app.displayId, TaskManager.displayIds(context))
                        message = when {
                            target == null -> "There is only one screen."
                            else -> TaskActions.bringTo(context, app.packageName, target)
                                ?: "Asked Android to move ${app.label} to the ${TaskPolicy.displayLabel(target).lowercase()}."
                        }
                    }
                    else -> Unit
                }
                true
            },
    ) {
        snapshot?.note?.let { note ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Limited app list", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                Text("  Info", color = MenuTokens.Value, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { showDetails = !showDetails }.padding(8.dp))
            }
            if (showDetails) Text(note, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        }
        // Scrolls, and MenuRow brings the selected row into view itself.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (snapshot == null) {
                Text("Reading the running apps...", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            } else if (apps.isEmpty()) {
                Text("No apps running", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            }
            SharedRunningAppsList(
                apps = apps,
                displays = TaskManager.displayIds(context),
                selectedIndex = index,
                clearLabel = if (index == 0 && message != null) message else "Clear all",
                rowMessage = { i -> if (i + 1 == index) message else null },
                onClear = { focusIndex = 0; press(GamepadAction.A) },
                onSwitch = { i, app -> focusIndex = i + 1; press(GamepadAction.A) },
                onClose = { i, app -> focusIndex = i + 1; press(GamepadAction.X) },
                onMove = { i, app -> focusIndex = i + 1; press(GamepadAction.Y) },
            )
        }
        HintRow(
            bindings = listOf(
                HintBinding(GamepadAction.A, "Clear all") { index == 0 },
                HintBinding(GamepadAction.A, "Switch to") { index > 0 },
                HintBinding(GamepadAction.X, "Close") { index > 0 },
                HintBinding(GamepadAction.Y, "Other screen") { index > 0 && TaskManager.displayIds(context).size > 1 },
                HintBinding(GamepadAction.B, "Back"),
            ),
            background = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Shared task rows for the Quick Menu and touch-only companion. [selectedIndex] is pad focus; buttons remain touch targets. */
@Composable
fun SharedRunningAppsList(
    apps: List<RunningApp>,
    displays: List<Int>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    clearLabel: String = "Clear all",
    rowMessage: (Int) -> String? = { null },
    onClear: () -> Unit,
    onSwitch: (Int, RunningApp) -> Unit,
    onClose: (Int, RunningApp) -> Unit,
    onMove: (Int, RunningApp) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing)) {
        SharedTaskAction(clearLabel, selectedIndex == 0, onClear)
        apps.forEachIndexed { i, app ->
            val selected = selectedIndex == i + 1
            Row(
                modifier = Modifier.fillMaxWidth().clip(MenuTokens.RowShape)
                    .background(if (selected) MenuTokens.SurfaceSelected else MenuTokens.Surface)
                    .clickable { onSwitch(i, app) }.heightIn(min = MenuTokens.RowMinHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.packageName, modifier = Modifier.padding(start = 12.dp))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(app.label, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(rowMessage(i) ?: TaskPolicy.displayLabel(app.displayId), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (displays.any { it != app.displayId }) SharedTaskAction("Switch to", false) { onMove(i, app) }
                SharedTaskAction("Close", false) { onClose(i, app) }
            }
        }
    }
}

@Composable
private fun SharedTaskAction(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) MenuTokens.Selected else MenuTokens.CardInset)
        .clickable(onClick = onClick).heightIn(min = 48.dp).widthIn(min = 64.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) MenuTokens.OnSelected else MenuTokens.OnSurface, style = MaterialTheme.typography.labelMedium)
    }
}

package dev.droidtop.shell.gamepad

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.tasks.TaskManager
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
 * A switches to the app on the screen it is on, X closes it, Y moves it to the other screen when there
 * is one. Which of those Android allows is the task manager's to say: a close that cannot be confirmed
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
    val focusRequester = remember { FocusRequester() }
    val press = rememberGamepadTouch()
    val index = focusIndex.coerceIn(0, (apps.size - 1).coerceAtLeast(0))

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(Unit) { TaskManager.watch(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPad { press ->
                val app = apps.getOrNull(index)
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN ->
                        focusIndex = menuStep(index, apps.size, if (press.action == GamepadAction.UP) -1 else 1)
                    GamepadAction.B -> onDismiss()
                    GamepadAction.A -> if (app != null) {
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
        snapshot?.note?.let {
            Text(
                it,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
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
                Text("Nothing is running that droidtop can see.", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            }
            apps.forEachIndexed { i, app ->
                MenuRow(
                    title = app.label,
                    subtitle = if (i == index && message != null) message else TaskPolicy.displayLabel(app.displayId),
                    selected = i == index,
                    leading = { AppIcon(app.packageName) },
                    onClick = {
                        focusIndex = i
                        press(GamepadAction.A)
                    },
                )
            }
        }
        HintRow(
            bindings = listOf(
                HintBinding(GamepadAction.A, "Switch to") { apps.isNotEmpty() },
                HintBinding(GamepadAction.X, "Close") { apps.isNotEmpty() },
                HintBinding(GamepadAction.Y, "Other screen") { apps.isNotEmpty() && TaskManager.displayIds(context).size > 1 },
                HintBinding(GamepadAction.B, "Back"),
            ),
            background = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

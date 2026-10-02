package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GamepadAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.consoles.LaunchFileAccessPrompt
import dev.droidtop.library.consoles.allFilesAccessIntent
import kotlinx.coroutines.launch

/**
 * The Gaming shell's launch-failure dialog (Droidtop/tracker#171): a
 * failed launch is a focused moment -- [LaunchFailureMessage]'s plain
 * sentence and a clear next step -- not raw exception text on a black
 * screen the pad's B cannot dismiss. The shell stays visible behind it;
 * the pad's B, a row tap, or a tap outside dismisses it. The fixes the
 * caller knows ([actions]: "Get an emulator" for a missing one, "Close it"
 * and "Return to droidtop" for the launch watchdog's alert) come first, and
 * the last row is always OK. [detail] is a muted line under the sentence.
 *
 * Built on [MenuPanel] like every other shell dialog (docs/SPEC.md 6e):
 * the dialog takes focus and the pipeline's front, and the pad's presses
 * are handled there.
 */
@Composable
internal fun LaunchFailureDialog(
    /** The plain sentence [LaunchFailureMessage] mapped the cause to. */
    message: String,
    /** The fixes on offer, in row order; empty when there is none. */
    actions: List<LaunchFailureAction>,
    onDismiss: () -> Unit,
    /** One muted line under [message], such as where the log is. */
    detail: String? = null,
) {
    // A launch that stopped for the emulator's file access (Droidtop/tracker#270) offers its two ways on: the
    // dialog adds them itself when the pending prompt is the one this message is about.
    val access = LaunchFileAccessPrompt.pending.collectAsState().value?.takeIf { it.message == message }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shown = remember(actions, access) {
        if (access == null) actions else listOf(
            LaunchFailureAction("Give access") {
                val opened = runCatching { context.startActivity(allFilesAccessIntent(access.packageName)) }.isSuccess ||
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                if (opened) {
                    LaunchFileAccessPrompt.clear()
                    onDismiss()
                }
            },
            LaunchFailureAction("Launch anyway") {
                LaunchFileAccessPrompt.clear()
                onDismiss()
                scope.launch {
                    runCatching { access.launchAnyway() }
                        .onFailure { android.util.Log.e("droidtop.LaunchFailureDialog", "Launch anyway failed", it) }
                }
            },
        ) + actions
    }
    val rows = remember(shown) { shown.map { it.label } + "OK" }
    val choose: (Int) -> Unit = { index -> if (index < shown.size) shown[index].run() else onDismiss() }
    var selected by remember { mutableIntStateOf(0) }

    val window = LocalShellWindow.current
    Dialog(onDismissRequest = onDismiss) {
        // The shell's one modal panel: focus, the pipeline's front for this
        // dialog and the pad's presses are handled there (docs/SPEC.md 6e).
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Launch failure",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, rows.size, press)
                    GamepadAction.A -> choose(selected)
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                message,
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            val shownDetail = detail ?: access?.let { "Without All files access it may open to a black screen." }
            if (shownDetail != null) {
                Text(
                    shownDetail,
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "A confirms · B closes",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            rows.forEachIndexed { index, row ->
                Text(
                    row,
                    color = if (index == selected) MenuTokens.OnSurface else MenuTokens.Value,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A row is a button here, so it is at least as
                        // big as a finger on a screen without a pad.
                        .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .selectionFrame(index == selected, RoundedCornerShape(8.dp), rest = Color.Transparent)
                        .clickable { choose(index) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** One fix a [LaunchFailureDialog] offers: its row's label and what choosing it does. */
internal class LaunchFailureAction(val label: String, val run: () -> Unit)

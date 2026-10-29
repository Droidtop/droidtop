package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding

/**
 * An offer the shell makes once (fetch box art, a theme is ready, set up Windows): a title, what it would do, and
 * two choices drawn by the one dialog component ([DialogChoices]). The way out ([decline]) comes first and has the
 * focus, so a mashed A starts nothing (DESIGN-LANGUAGE "Modals", Droidtop/tracker#669); B is the way out too, and
 * the footer names it so. No legend in the body: the footer is the one place controls are named.
 */
@Composable
internal fun OfferDialog(
    title: String,
    body: String?,
    action: String,
    decline: String,
    focusLabel: String,
    onAction: () -> Unit,
    onDecline: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    fun choose(index: Int) = if (index == 1) onAction() else onDecline()
    val hints = remember(decline) { listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, decline)) }
    Dialog(onDismissRequest = onDecline) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(400.dp)),
            focusLabel = focusLabel,
            hints = hints,
            title = title,
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, 2, press)
                    GamepadAction.A -> choose(selected)
                    GamepadAction.B -> onDecline()
                    else -> Unit
                }
                true
            },
        ) {
            body?.let { Text(it, color = MenuTokens.Value, style = TypeRole.body, modifier = Modifier.padding(bottom = Space.Sm)) }
            DialogChoices(
                groups = listOf(listOf(DialogChoice(decline)), listOf(DialogChoice(action))),
                selected = selected,
                onChoose = { selected = it; choose(it) },
            )
        }
    }
}

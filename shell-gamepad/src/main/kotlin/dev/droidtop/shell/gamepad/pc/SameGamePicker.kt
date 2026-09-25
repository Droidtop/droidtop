package dev.droidtop.shell.gamepad.pc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuHint
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.launch

/** One game the picker offers: its name, and why it is offered and where it is. */
internal data class SameGameChoice(val title: String, val subtitle: String)

/**
 * "This game IS that one": the one picker behind every "these two are the
 * same game" question on a game's screen (docs/SPEC.md 7g and 7m).
 *
 * Three questions use it, and they are one question: from a detected game,
 * which missing game it replaces; from a missing game, which detected game
 * replaced it; and from a game that is here, which other game in the
 * library it is the same as. Each caller orders its own list (the fold by
 * [dev.droidtop.library.MissingGames], the merge by
 * [dev.droidtop.library.SimilarGames], both Pythia's order: the same name
 * first, then names at least 0.6 alike, most alike first) and says what
 * picking does; the picker draws the list and asks twice.
 *
 * Twice, because both are one-way changes to what the library knows: the
 * first A (or tap) on a row arms it and its line says what will happen,
 * the second confirms, and moving to another row disarms (the design
 * language's two-step confirm).
 */
@Composable
internal fun SameGamePicker(
    /** The panel's name for focus logging, and its heading. */
    focusLabel: String,
    question: String,
    choices: List<SameGameChoice>,
    /** What an armed row says: the change the second press makes. */
    confirmLine: (SameGameChoice) -> String,
    workingLine: String,
    /** Does the change for choice [index] and returns the sentence to show. */
    onPick: suspend (index: Int) -> String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var focusIndex by remember(choices) { mutableIntStateOf(0) }
    var armed by remember(choices) { mutableStateOf<Int?>(null) }
    var working by remember(choices) { mutableStateOf(false) }

    fun press(index: Int) {
        if (working) return
        focusIndex = index
        if (armed != index) {
            armed = index
            return
        }
        working = true
        scope.launch {
            val message = onPick(index)
            working = false
            armed = null
            onDone(message)
        }
    }

    fun move(to: Int) {
        focusIndex = to
        armed = null
        EsDeNavigationSounds.play("scroll")
    }

    BackHandler { onDismiss() }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(620.dp)),
            focusLabel = focusLabel,
            onKey = { event ->
                if (event.type != KeyEventType.KeyUp) {
                    false
                } else {
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.UP -> {
                            if (choices.isNotEmpty()) move((focusIndex - 1 + choices.size) % choices.size)
                            true
                        }
                        GamepadAction.DOWN -> {
                            if (choices.isNotEmpty()) move((focusIndex + 1) % choices.size)
                            true
                        }
                        GamepadAction.A -> {
                            if (choices.isNotEmpty()) press(focusIndex)
                            true
                        }
                        GamepadAction.B, GamepadAction.BACK -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                }
            },
        ) {
            Text(
                question,
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            if (choices.isEmpty()) {
                MenuRow(title = "No game here is named anything like this one")
            } else {
                choices.forEachIndexed { index, choice ->
                    MenuRow(
                        title = choice.title,
                        subtitle = if (armed == index) confirmLine(choice) else choice.subtitle,
                        subtitleLines = if (armed == index) 2 else 1,
                        selected = index == focusIndex,
                        onClick = { press(index) },
                    )
                }
            }
            if (working) MenuHint(workingLine)
            MenuHint("Up/Down moves, A picks and A again confirms, B closes")
        }
    }
}

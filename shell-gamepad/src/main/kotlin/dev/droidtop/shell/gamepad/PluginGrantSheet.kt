package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.droidtop.pluginhost.GrantAnswer
import dev.droidtop.pluginhost.PermissionTier
import dev.droidtop.pluginhost.PluginGrantPrompts
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow

/**
 * The first-use sheet (docs/plugin-api.md 4.3): "<Plugin> wants to <label>", the plugin's own reason, and Allow /
 * Not now / Never allow. One sheet for every mode: :app's sheet activity draws it over whatever is on screen (Gaming's
 * shell, the Android home screen, the Desktop), in the Gaming theme when Gaming was the last mode, the way the mode
 * switcher is drawn. A plugin never draws anything itself: the words are the registry's label and the plugin's reason.
 *
 * Not now holds the cursor at the start, so a mashed A changes nothing (the safe choice first, design language
 * "Modals"); Up and Down move, A chooses, B is Not now. The window hosting it has no shell footer, so the panel draws
 * its own hint row, which is also the touch route to A and B.
 */
@Composable
fun PluginGrantSheet(pending: PluginGrantPrompts.Pending) {
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        val request = pending.request
        val choices = remember(pending) {
            listOf(
                "Allow" to GrantAnswer.ALLOW,
                "Not now" to GrantAnswer.NOT_NOW,
                "Never allow" to GrantAnswer.NEVER,
            )
        }
        var selected by remember(pending) { mutableStateOf(1) }
        val answer: (GrantAnswer) -> Unit = { PluginGrantPrompts.answer(pending, it) }
        val window = LocalShellWindow.current

        // A dismissal that is not a choice (the system closing the dialog) is Not now, never Allow.
        ShellDialog(onDismissRequest = { answer(GrantAnswer.NOT_NOW) }) {
            GatePadInThisDialog()
            MenuPanel(
                modifier = Modifier.width(window.panelWidth(460.dp)),
                focusLabel = "Plugin permission sheet",
                hints = emptyList(),
                onPad = { press ->
                    when (press.action) {
                        GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, choices.size, press)
                        GamepadAction.A -> answer(choices[selected].second)
                        GamepadAction.B -> answer(GrantAnswer.NOT_NOW)
                        else -> Unit
                    }
                    true
                },
            ) {
                Text(
                    "${request.pluginLabel} wants to ${request.permissionLabel.replaceFirstChar { it.lowercase() }}",
                    color = MenuTokens.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                request.reason?.let {
                    Text(it, color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium)
                }
                if (request.tier == PermissionTier.CRITICAL) {
                    Text(
                        "This gives the plugin system-level access. Only allow it if you trust the plugin.",
                        color = MenuTokens.NoticeDanger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                choices.forEachIndexed { index, (label, value) ->
                    MenuRow(
                        title = label,
                        selected = index == selected,
                        danger = value == GrantAnswer.NEVER,
                        onClick = {
                            selected = index
                            answer(value)
                        },
                    )
                }
                HintRow(
                    bindings = listOf(HintBinding(GamepadAction.A, "Choose"), HintBinding(GamepadAction.B, "Not now")),
                    background = androidx.compose.ui.graphics.Color.Transparent,
                )
            }
        }
    }
}

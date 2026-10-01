package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.pluginhost.GrantAnswer
import dev.droidtop.pluginhost.PermissionTier
import dev.droidtop.pluginhost.PluginGrantPrompts
import dev.droidtop.shell.gamepad.input.GamepadAction

/**
 * Where the first-use sheet is drawn (docs/plugin-api.md 4.3). Placed once
 * in the shell: while it is composed it tells [PluginGrantPrompts] that a
 * surface can show the sheet, so a plugin's call in `ask` state during a
 * user-initiated call prompts here, and anywhere no such surface exists the
 * call is answered Not now and the permission is granted from the plugin's
 * Permissions screen instead. A plugin never draws anything itself: the
 * words are the registry's label and the plugin's own reason.
 */
@Composable
internal fun PluginGrantSheetHost() {
    DisposableEffect(Unit) {
        PluginGrantPrompts.attachHost()
        onDispose { PluginGrantPrompts.detachHost() }
    }
    val pending by PluginGrantPrompts.pending.collectAsState()
    pending?.let { PluginGrantSheet(it) }
}

@Composable
private fun PluginGrantSheet(pending: PluginGrantPrompts.Pending) {
    val request = pending.request
    // Up and Down pick, A acts, B is Not now (docs/plugin-api.md 4.3).
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
    Dialog(onDismissRequest = { answer(GrantAnswer.NOT_NOW) }) {
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(460.dp)),
            focusLabel = "Plugin permission sheet",
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
                    color = MenuTokens.Danger,
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
            TouchHintBar(
                hints = listOf(GamepadAction.A to "Choose", GamepadAction.B to "Not now"),
                background = Color.Transparent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

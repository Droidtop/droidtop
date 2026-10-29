package dev.droidtop.shell.gamepad

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.pluginhost.PluginSecrets
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow

/**
 * A plugin's secret, on droidtop's own sheet (docs/plugin-api.md 1.10, `secret`): its label, the value, and Copy and
 * Close. Drawn only while open; the value is not kept anywhere once it closes. Copy marks the clip sensitive, so
 * Android does not show it in its own preview. Up and Down move, A chooses, B closes.
 */
@Composable
fun PluginSecretSheet(shown: PluginSecrets.Shown, onClose: () -> Unit) {
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        val context = LocalContext.current
        var selected by remember(shown) { mutableStateOf(0) }
        var copied by remember(shown) { mutableStateOf(false) }
        val window = LocalShellWindow.current
        val copy = {
            copySensitive(context, shown.label, shown.value)
            copied = true
        }
        val choices = listOf("Copy" to copy, "Close" to onClose)

        Dialog(onDismissRequest = onClose) {
            GatePadInThisDialog()
            MenuPanel(
                modifier = Modifier.width(window.panelWidth(460.dp)),
                focusLabel = "${shown.pluginLabel}: ${shown.label}",
                hints = emptyList(),
                onPad = { press ->
                    when (press.action) {
                        GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, choices.size, press)
                        GamepadAction.A -> choices[selected].second()
                        GamepadAction.B -> onClose()
                        else -> Unit
                    }
                    true
                },
            ) {
                Text(shown.label, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("From ${shown.pluginLabel}", color = MenuTokens.Value, style = MaterialTheme.typography.bodySmall)
                Text(shown.value, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                choices.forEachIndexed { index, (label, run) ->
                    MenuRow(
                        title = label,
                        value = if (index == 0 && copied) "Copied" else null,
                        selected = index == selected,
                        onClick = {
                            selected = index
                            run()
                        },
                    )
                }
                HintRow(
                    bindings = listOf(HintBinding(GamepadAction.A, "Choose"), HintBinding(GamepadAction.B, "Close")),
                    background = androidx.compose.ui.graphics.Color.Transparent,
                )
            }
        }
    }
}

/** Puts [value] on the clipboard marked sensitive (Android 13 and later keep it out of the clipboard preview). */
private fun copySensitive(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

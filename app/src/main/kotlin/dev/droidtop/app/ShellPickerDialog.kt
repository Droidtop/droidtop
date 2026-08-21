package dev.droidtop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class ShellOption(val kind: ShellKind, val label: String, val description: String)

private val SHELL_OPTIONS = listOf(
    ShellOption(ShellKind.STANDARD, "Standard", "A normal Android app-icon grid — set DroidTop as your home screen to use it that way."),
    ShellOption(ShellKind.DESKTOP, "Desktop", "Taskbar + start menu around the shared desktop (Windows/Linux apps)."),
    ShellOption(ShellKind.HANDHELD, "Handheld", "Full-screen, controller-navigable — Playnite/Android-TV style."),
)

@Composable
fun ShellPickerDialog(current: ShellKind, onSelect: (ShellKind) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch shell") },
        text = {
            Column {
                SHELL_OPTIONS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.kind) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.kind == current, onClick = { onSelect(option.kind) })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(option.label)
                            Text(option.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

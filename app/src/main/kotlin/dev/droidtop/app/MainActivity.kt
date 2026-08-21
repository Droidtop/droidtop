package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidtop.library.Library
import dev.droidtop.library.NativeAppProvider
import dev.droidtop.shell.desktop.DesktopShell
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.DefaultShell

/**
 * Hosts whichever of the three shells the user has picked
 * (`dev.droidtop.app.ShellPreference`) — [ShellKind.STANDARD]
 * (`:shell-default`, a normal Android app-icon grid — this is also what
 * renders when DroidTop is chosen as the device's home screen, see the
 * HOME/DEFAULT intent-filter in AndroidManifest.xml), [ShellKind.DESKTOP]
 * (`:shell-desktop`, taskbar + start menu around the shared desktop), or
 * [ShellKind.HANDHELD] (`:shell-gamepad`, full-screen controller-navigable).
 * All three read the exact same [Library] — switching shells is just
 * rendering a different Composable, never a different app build.
 *
 * :shell-desktop needs a live HostBridge/DisplayOutput to show anything but
 * its own "no desktop session" placeholder — DesktopSessionService (which is
 * supposed to create the primary container and connect one) is still a TODO
 * stub, so `null`/`null` is passed here for now. See shell-desktop/README.md.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val library = Library(listOf(NativeAppProvider(applicationContext)))

        setContent {
            var activeShell by remember { mutableStateOf(ShellPreference.get(applicationContext)) }
            var pickerOpen by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxSize()) {
                when (activeShell) {
                    ShellKind.STANDARD -> DefaultShell(library)
                    ShellKind.DESKTOP -> DesktopShell(library, hostBridge = null, primaryOutput = null)
                    ShellKind.HANDHELD -> GamepadShell(library)
                }

                // Small, unobtrusive entry point to switch shells — every
                // shell gets this for free here rather than each one needing
                // its own settings screen.
                Button(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Text("Shell")
                }

                if (pickerOpen) {
                    ShellPickerDialog(
                        current = activeShell,
                        onSelect = { selected ->
                            activeShell = selected
                            ShellPreference.set(applicationContext, selected)
                            pickerOpen = false
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
            }
        }
    }
}

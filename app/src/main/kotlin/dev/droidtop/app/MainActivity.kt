package dev.droidtop.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * Secondary entry point (regular app-drawer icon) for switching to the
 * Desktop/Handheld shells or re-opening the Standard launcher.
 *
 * The real HOME/LAUNCHER entry point — [ShellKind.STANDARD] — is
 * `com.android.launcher3.Launcher`, a real standalone Activity forked in
 * from Murine Launcher (`:shell-default`, see its own README.md), not
 * something this Activity renders inline: unlike the other two shells, a
 * launcher's whole architecture assumes it owns the process as the HOME
 * activity, so "switching to Standard" here means launching it via
 * [Intent], the same way any launcher-picker would.
 *
 * [ShellKind.DESKTOP] (`:shell-desktop`, taskbar + start menu around the
 * shared desktop) and [ShellKind.HANDHELD] (`:shell-gamepad`, full-screen
 * controller-navigable) both remain Composables rendered directly here,
 * reading the same [Library] as Standard's NativeAppProvider.
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
                    ShellKind.STANDARD -> StandardShellLaunchPrompt(onLaunch = { launchStandardShell() })
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
                            if (selected == ShellKind.STANDARD) {
                                launchStandardShell()
                            }
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
            }
        }
    }

    private fun launchStandardShell() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(this@MainActivity, "com.android.launcher3.Launcher")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

@Composable
private fun StandardShellLaunchPrompt(onLaunch: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text("Standard is a real, separate home-screen app (not rendered here).")
        Button(onClick = onLaunch, modifier = Modifier.padding(top = 16.dp)) {
            Text("Open Standard launcher")
        }
    }
}

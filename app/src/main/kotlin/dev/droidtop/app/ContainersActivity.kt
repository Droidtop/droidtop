package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.droidtop.app.settings.AppSettingsCatalogs
import dev.droidtop.app.settings.ContainersCatalog
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.TouchHintBar
import dev.droidtop.shell.gamepad.currentShellWindow

/**
 * The container manager's host for the Desktop taskbar, which is not
 * already inside a settings surface: the [ContainersCatalog] screen drawn
 * by the same [CatalogNavigator] as every other settings screen, in
 * droidtop's dark look, with the shell's hint row (the touch route to A,
 * B and Y). Everything the screen shows and does lives in the catalog;
 * settings surfaces reach the same screen by its registry id.
 */
class ContainersActivity : AppCompatActivity() {
    // Re-read when the screen comes back to the front: the desktop may have
    // started or stopped while another screen was on top.
    private var resumed by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettingsCatalogs.ensureRegistered()
        val screen = SettingsScreenRegistry.get(ContainersCatalog.SCREEN_ID)!!
        setContent {
            val session by DesktopSessionService.state.collectAsState()
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
                    Column(Modifier.fillMaxSize().background(MenuTokens.Ground)) {
                        Box(Modifier.weight(1f)) {
                            CatalogNavigator(
                                root = screen,
                                onExit = { finish() },
                                refreshKey = session::class to resumed,
                            )
                        }
                        TouchHintBar(
                            hints = listOf(
                                GamepadAction.A to "Select",
                                GamepadAction.B to "Back",
                                GamepadAction.Y to "Info",
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed++
    }
}

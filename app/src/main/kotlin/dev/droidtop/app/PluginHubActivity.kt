package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.droidtop.app.settings.AppSettingsCatalogs
import dev.droidtop.library.integrations.PluginHub
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow

/**
 * Where a plugin page opens in Standard and Desktop (docs/plugin-api.md 1.9): the Plugins list and a panel from the
 * home screen's menu or the taskbar, a taskbar item's menu, a Start menu action's answer, the plugin actions on an
 * app or a window. The page is a [PluginHub] page drawn by the same [CatalogNavigator], dark look and hint row as
 * the Containers screen, so a plugin looks and drives the same in every mode; it never draws anything itself.
 */
class PluginHubActivity : AppCompatActivity() {
    private var resumed by mutableStateOf(0)
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettingsCatalogs.ensureRegistered()
        token = intent?.getStringExtra(PluginHub.EXTRA_TOKEN)
        // The page lives in this process only; after the process was gone there is nothing to show.
        val screen = PluginHub.screen(token) ?: run {
            finish()
            return
        }
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
                    Column(Modifier.fillMaxSize().groundBackground()) {
                        Box(Modifier.weight(1f)) {
                            CatalogNavigator(root = screen, onExit = { finish() }, refreshKey = resumed)
                        }
                        HintRow(
                            bindings = listOf(
                                HintBinding(GamepadAction.A, "Select"),
                                HintBinding(GamepadAction.B, "Back"),
                                HintBinding(GamepadAction.Y, "Info"),
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

    override fun onDestroy() {
        if (isFinishing) PluginHub.release(token)
        super.onDestroy()
    }
}

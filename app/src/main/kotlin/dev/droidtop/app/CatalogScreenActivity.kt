package dev.droidtop.app

import dev.droidtop.shell.gamepad.groundBackground
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.currentShellWindow

/**
 * droidtop's host for one registered catalog screen outside any shell ([CatalogScreenLink]): the
 * places (Stores, Social, Downloads and installs, Updates, Plugins; docs/SPEC.md 7j "Places") for
 * Standard and Desktop, which have no left menu, and the container manager for the Desktop taskbar.
 * The screen is drawn by the same [CatalogNavigator] Gaming's places and Settings use, in droidtop's
 * dark look, with the shell's hint row (the touch route to A, B and Y), so a place looks and behaves
 * the same whichever mode opened it. Everything a screen shows and does lives in its catalog.
 *
 * It was `ContainersActivity`, hard-wired to the container manager; one host for every screen
 * replaces a host per screen (Droidtop/tracker#346).
 */
class CatalogScreenActivity : AppCompatActivity() {
    // Re-read when the screen comes back to the front: the desktop may have
    // started or stopped, or a store signed in, while another screen was on top.
    private var resumed by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettingsCatalogs.ensureRegistered()
        val screenId = intent?.getStringExtra(CatalogScreenLink.EXTRA_SCREEN_ID)
        // Kiosk and Kid hide the places in every mode, as Gaming's left menu does: a shortcut or a
        // notification must not be a way around that.
        val place = Place.byScreenId(screenId)
        if (place != null && place !in Place.visible(UiModePrefs.get(this))) {
            Toast.makeText(this, "${place.title} is hidden in this UI mode", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val screen = screenId?.let { SettingsScreenRegistry.get(it) }
        if (screen == null) {
            android.util.Log.w("droidtop.Screen", "No catalog screen registered as '$screenId'")
            finish()
            return
        }
        setContent {
            val session by DesktopSessionService.state.collectAsState()
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
                    Column(Modifier.fillMaxSize().groundBackground()) {
                        Box(Modifier.weight(1f)) {
                            CatalogNavigator(
                                root = screen,
                                onExit = { finish() },
                                refreshKey = session::class to resumed,
                            )
                        }
                        // The shell's hint row around the navigator, built
                        // the one way every row is now: gated bindings.
                        // All three dispatch inside the navigator -- A
                        // activates the row, Y is its Info sheet, B pops
                        // one level (and leaves the activity at the root).
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
}

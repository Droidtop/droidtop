package dev.droidtop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import dev.droidtop.shell.gamepad.ModeSwitcherChoice
import dev.droidtop.shell.gamepad.ModeSwitcherSheet
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.HomeRolePrefs

/**
 * The mode switcher's one host (docs/SPEC.md 2c, "Switching modes is named on every surface"): every
 * route opens it through `ModeSwitcher.open` (the long press of Back, the home screen's menu, Gaming's
 * Quick Menu, the Desktop taskbar), and it draws the shell's own modal sheet ([ModeSwitcherSheet]) over
 * whatever is on screen, in the Gaming theme when Gaming was the last mode and droidtop's own look
 * otherwise. The rows and what they do are [BackButtonMenu.choices]. The activity goes when the sheet does.
 *
 * "Android" while droidtop's launcher is on but not Android's Home app does not switch: it asks, "Android mode
 * needs droidtop as Home", and Set requests the Home role (Droidtop/tracker#522). Another launcher
 * would take a screen of a dual-screen device and leave droidtop's surfaces behind.
 */
/** A mode's glyph in the switcher: the same marks Global settings' mode rows use. */
private fun modeIcon(mode: dev.droidtop.library.settings.Mode): dev.droidtop.library.settings.CatalogIcon = when (mode) {
    dev.droidtop.library.settings.Mode.LAUNCHER -> dev.droidtop.library.settings.CatalogIcon.STANDARD
    dev.droidtop.library.settings.Mode.GAMING -> dev.droidtop.library.settings.CatalogIcon.GAMING
    dev.droidtop.library.settings.Mode.DESKTOP -> dev.droidtop.library.settings.CatalogIcon.DESKTOP
}

class ModeSwitcherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The switcher is open: Mobile's "Press and hold Home" hint has done its job (Droidtop/tracker#829).
        val app = applicationContext
        dev.droidtop.runtime.util.AppScopes.io.launch { dev.droidtop.library.settings.Hints.markDone(app, dev.droidtop.library.settings.Hints.HOLD_HOME) }
        // Over Gaming or Desktop, which hide Android's bars, the sheet hides them too (Droidtop/tracker#750); over
        // Standard, Android's home screen, they stay.
        val immersive = dev.droidtop.library.settings.Modes.lastMode(this) != dev.droidtop.library.settings.Mode.LAUNCHER.id
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                // The question and the Home role request both outlive the first sheet's own dismissal: the sheet
                // dismisses after every choice, and this activity must stay for the request's answer.
                var asking by remember { mutableStateOf(false) }
                var requesting by remember { mutableStateOf(false) }
                val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    requesting = false
                    if (HomeRolePrefs.isDroidtopHome(this@ModeSwitcherActivity)) {
                        BackButtonMenu.openHome(this@ModeSwitcherActivity, HomeRolePrefs.activeHomeImplementation(this@ModeSwitcherActivity))
                    }
                    finish()
                }
                if (asking) {
                    val answers = remember {
                        listOf(
                            ModeSwitcherChoice("Set") {
                                requesting = true
                                request.launch(HomeRolePrefs.homeRequestIntent(this@ModeSwitcherActivity))
                            },
                            ModeSwitcherChoice("Cancel") {},
                        )
                    }
                    ModeSwitcherSheet(
                        choices = answers,
                        title = "Mobile mode needs droidtop as Home",
                        onDismiss = { if (!requesting) finish() },
                        hideSystemBars = immersive,
                    )
                } else {
                    val choices = remember {
                        val current = dev.droidtop.library.settings.Modes.lastMode(this@ModeSwitcherActivity)
                        BackButtonMenu.choices(this@ModeSwitcherActivity, askForHome = { asking = true }).map { choice ->
                            ModeSwitcherChoice(
                                label = choice.label,
                                icon = choice.mode?.let(::modeIcon),
                                current = choice.mode?.id == current,
                                apart = choice.mode == null,
                                choose = choice.choose,
                            )
                        }
                    }
                    ModeSwitcherSheet(choices = choices, onDismiss = { if (!asking) finish() }, hideSystemBars = immersive)
                }
            }
        }
    }
}

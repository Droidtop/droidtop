package dev.droidtop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import dev.droidtop.shell.gamepad.ModeSwitcherChoice
import dev.droidtop.shell.gamepad.ModeSwitcherSheet
import dev.droidtop.shell.standard.BackButtonMenu

/**
 * The mode switcher's one host (docs/SPEC.md 2c, "Switching modes is named on every surface"): every
 * route opens it through `ModeSwitcher.open` (the long press of Back, the home screen's menu, Gaming's
 * Quick Menu, the Desktop taskbar), and it draws the shell's own modal sheet ([ModeSwitcherSheet]) over
 * whatever is on screen, in the Gaming theme when Gaming was the last mode and droidtop's own look
 * otherwise. The rows and what they do are [BackButtonMenu.choices]. The activity goes when the sheet does.
 */
class ModeSwitcherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                val choices = remember {
                    BackButtonMenu.choices(this@ModeSwitcherActivity).map { (label, choose) -> ModeSwitcherChoice(label, choose) }
                }
                ModeSwitcherSheet(choices = choices, onDismiss = { finish() })
            }
        }
    }
}

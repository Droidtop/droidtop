package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.droidtop.pluginhost.GrantAnswer
import dev.droidtop.pluginhost.PluginGrantPrompts
import dev.droidtop.shell.gamepad.PluginGrantSheet

/**
 * The first-use sheet's one window (docs/plugin-api.md 4.3, Droidtop/tracker#128). The broker asks
 * [PluginGrantPrompts], which opens this activity through [open]; it draws [PluginGrantSheet] over whatever is on
 * screen, Gaming's shell, the Android home screen or the Desktop, the way [ModeSwitcherActivity] draws the mode
 * switcher, and goes when the question does (answered, or Not now after the broker's wait). Leaving it any other way
 * (Home, Recents) answers Not now, so the plugin's call is not held for the rest of the wait.
 */
class PluginGrantSheetActivity : ComponentActivity() {
    /** The question this window drew last: the one leaving it answers. */
    private var shown: PluginGrantPrompts.Pending? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = true
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                val pending by PluginGrantPrompts.pending.collectAsState()
                val current = pending
                if (current == null || isFinishing) {
                    LaunchedEffect(Unit) { close() }
                } else {
                    shown = current
                    PluginGrantSheet(current)
                }
            }
        }
    }

    private fun close() {
        live = false
        if (!isFinishing) finish()
    }

    override fun onStop() {
        super.onStop()
        // Gone from the screen without a choice (Home, Recents, another app in front): that is Not now.
        if (!isChangingConfigurations) {
            shown?.let { PluginGrantPrompts.answer(it, GrantAnswer.NOT_NOW) }
            close()
        }
    }

    override fun onDestroy() {
        if (isFinishing) live = false
        super.onDestroy()
    }

    companion object {
        /** Whether a sheet window is up and not on its way out: a new question goes to it instead of a second window. */
        @Volatile private var live = false

        /** [PluginGrantPrompts]' surface: brings the sheet's window up, from the broker's binder thread. */
        fun open(context: Context): Boolean {
            if (live) return true
            return runCatching {
                context.startActivity(
                    Intent(context, PluginGrantSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        }
    }
}

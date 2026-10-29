package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.droidtop.pluginhost.PluginSecrets
import dev.droidtop.shell.gamepad.PluginSecretSheet

/**
 * The window of a plugin's secret (docs/plugin-api.md 1.10, `secret`): [PluginSecrets] opens it through [open] and it
 * draws [PluginSecretSheet] over whatever is on screen, as the first-use sheet's window does. The window is secure, so
 * the value is in no screenshot, screen recording or Recents thumbnail, and leaving it any way closes the sheet and lets
 * the value go.
 */
class PluginSecretSheetActivity : ComponentActivity() {
    private var shown: PluginSecrets.Shown? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        live = true
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                val current by PluginSecrets.shown.collectAsState()
                val now = current
                if (now == null || isFinishing) {
                    LaunchedEffect(Unit) { close() }
                } else {
                    shown = now
                    PluginSecretSheet(now) { PluginSecrets.close(now) }
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
        // Gone from the screen (Home, Recents, another app in front): the sheet closes and the value goes with it.
        if (!isChangingConfigurations) {
            shown?.let { PluginSecrets.close(it) }
            shown = null
            close()
        }
    }

    override fun onDestroy() {
        if (isFinishing) live = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var live = false

        /** [PluginSecrets]' surface: brings the sheet's window up. */
        fun open(context: Context): Boolean {
            if (live) return true
            return runCatching {
                context.startActivity(Intent(context, PluginSecretSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}

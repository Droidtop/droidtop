package dev.droidtop.app

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import dev.droidtop.pluginhost.PluginSecrets
import dev.droidtop.shell.gamepad.PluginSecretSheet

/**
 * The window of a plugin's secret (docs/plugin-api.md 1.10, `secret`): [PluginSecrets] opens it through [open] and it
 * draws [PluginSecretSheet] as the first-use sheet's window does. It is secure, so the value is in no screenshot,
 * screen recording or Recents thumbnail, and leaving it any way closes the sheet and lets the value go.
 */
class PluginSecretSheetActivity : SheetHostActivity<PluginSecrets.Shown>() {
    override val question: StateFlow<PluginSecrets.Shown?> get() = PluginSecrets.shown

    override val secure = true

    override fun onGone(item: PluginSecrets.Shown) = PluginSecrets.close(item)

    @Composable
    override fun Sheet(item: PluginSecrets.Shown) = PluginSecretSheet(item) { PluginSecrets.close(item) }

    companion object {
        /** [PluginSecrets]' surface. */
        fun open(context: Context): Boolean = SheetHostActivity.open(context, PluginSecretSheetActivity::class.java)
    }
}

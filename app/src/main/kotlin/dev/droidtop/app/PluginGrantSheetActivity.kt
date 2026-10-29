package dev.droidtop.app

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import dev.droidtop.pluginhost.GrantAnswer
import dev.droidtop.pluginhost.PluginGrantPrompts
import dev.droidtop.shell.gamepad.PluginGrantSheet

/**
 * The first-use sheet's one window (docs/plugin-api.md 4.3, Droidtop/tracker#128). The broker asks
 * [PluginGrantPrompts], which opens this activity through [open]; it draws [PluginGrantSheet] the way
 * [ModeSwitcherActivity] draws the mode switcher, and goes when the question does (answered, or Not now after the
 * broker's wait). Leaving it any other way answers Not now.
 */
class PluginGrantSheetActivity : SheetHostActivity<PluginGrantPrompts.Pending>() {
    override val question: StateFlow<PluginGrantPrompts.Pending?> get() = PluginGrantPrompts.pending

    override fun onGone(item: PluginGrantPrompts.Pending) = PluginGrantPrompts.answer(item, GrantAnswer.NOT_NOW)

    @Composable
    override fun Sheet(item: PluginGrantPrompts.Pending) = PluginGrantSheet(item)

    companion object {
        /** [PluginGrantPrompts]' surface. */
        fun open(context: Context): Boolean = SheetHostActivity.open(context, PluginGrantSheetActivity::class.java)
    }
}

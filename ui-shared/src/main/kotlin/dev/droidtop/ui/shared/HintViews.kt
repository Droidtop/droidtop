package dev.droidtop.ui.shared

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.Hints
import dev.droidtop.runtime.util.AppScopes
import dev.droidtop.shell.gamepad.GamingTheme
import dev.droidtop.shell.gamepad.InPlaceHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-place hint for a View surface (Mobile is the forked Launcher3; docs/DESIGN-LANGUAGE.md "Guidance",
 * Droidtop/tracker#829): [show] puts [InPlaceHint] at the foot of [activity]'s content, above the navigation bar, in the
 * colours outside Gaming, unless [id] is already done. It goes when the person taps Got it (which marks it done) or the
 * moment something else marks it done ([Hints.changes]). One at a time per window; asking again while it shows does
 * nothing.
 */
object HintViews {
    fun show(activity: Activity, id: String, text: String, ready: (android.content.Context) -> Boolean = { true }) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val tag = "hint:$id"
        if (content.findViewWithTag<android.view.View>(tag) != null) return
        val app = activity.applicationContext
        AppScopes.main.launch {
            val due = withContext(Dispatchers.IO) { !Hints.done(app, id) && ready(app) }
            if (!due || activity.isFinishing || content.findViewWithTag<android.view.View>(tag) != null) return@launch
            val host = ComposeView(activity).apply { this.tag = tag }
            val close = { content.post { content.removeView(host) } }
            host.setContent {
                LaunchedEffect(Unit) {
                    Hints.changes.collect { if (withContext(Dispatchers.IO) { Hints.done(app, id) }) close() }
                }
                GamingTheme(outsideGaming = true) {
                    InPlaceHint(
                        text = text,
                        onDismiss = {
                            close()
                            AppScopes.io.launch { Hints.markDone(app, id) }
                        },
                        modifier = Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = HINT_LIFT),
                    )
                }
            }
            content.addView(
                host,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
            )
        }
    }

    /** How far above the navigation bar a hint sits: clear of Mobile's dock. */
    private val HINT_LIFT = 120.dp
}

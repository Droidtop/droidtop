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
    /** Whether an in-place hint is up; the pad row waits for it, since both sit at the foot above the dock. */
    private val inPlaceShowing = androidx.compose.runtime.mutableStateOf(false)

    fun show(activity: Activity, id: String, text: String, ready: (android.content.Context) -> Boolean = { true }) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val tag = "hint:$id"
        if (content.findViewWithTag<android.view.View>(tag) != null) return
        val app = activity.applicationContext
        AppScopes.main.launch {
            val due = withContext(Dispatchers.IO) { !Hints.done(app, id) && ready(app) }
            if (!due || activity.isFinishing || content.findViewWithTag<android.view.View>(tag) != null) return@launch
            val host = ComposeView(activity).apply { this.tag = tag }
            val close = {
                content.post {
                    content.removeView(host)
                    inPlaceShowing.value = false
                }
            }
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
            inPlaceShowing.value = true
        }
    }

    /**
     * The pad's hint row for a View surface (Mobile's home, Droidtop/tracker#699): the shared [HintRow] over exactly
     * [bindings] whose conditions hold, at the foot of [activity]'s content above the dock, drawn only while a pad is
     * what the person last used ([PadModality]) and gone at the first touch. Its pills sit on their own small plates
     * over the wallpaper, and a tap on one is that button's press. Once per window; asking again does nothing.
     * [bindings] is built again whenever [revision] changes, since what it reads (the page, the focus) is the View
     * surface's own state, which Compose does not observe. It waits while an in-place hint ([show]) is up.
     */
    fun padRow(
        activity: Activity,
        revision: androidx.compose.runtime.State<Int>,
        bindings: () -> List<dev.droidtop.shell.gamepad.input.HintBinding>,
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<android.view.View>(PAD_ROW_TAG) != null) return
        val host = ComposeView(activity).apply { tag = PAD_ROW_TAG }
        host.setContent {
            GamingTheme(outsideGaming = true) {
                if (dev.droidtop.shell.gamepad.input.PadModality.showsFocus && !inPlaceShowing.value) {
                    val tick = revision.value
                    val current = androidx.compose.runtime.remember(tick) { bindings() }
                    dev.droidtop.shell.gamepad.input.HintRow(
                        bindings = current,
                        background = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.navigationBarsPadding().padding(bottom = HINT_LIFT),
                    )
                }
            }
        }
        content.addView(
            host,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    private const val PAD_ROW_TAG = "hint:pad-row"

    /** How far above the navigation bar a hint sits: clear of Mobile's dock. */
    private val HINT_LIFT = 120.dp
}

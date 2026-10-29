package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow

/**
 * The one window that draws a sheet a plugin's call is waiting on, over whatever is on screen (Gaming's shell, the
 * Android home screen or the Desktop): a question in [question] opens it through [open], it draws [Sheet] while the
 * question stands and goes when the question does. Leaving it any other way (Home, Recents, another app in front)
 * runs [onGone] for the question it drew last, so the plugin's call is not held. [secure] keeps the window out of
 * screenshots, recordings and the Recents thumbnail. The first-use sheet and a plugin's secret are its two kinds.
 */
abstract class SheetHostActivity<T : Any> : ComponentActivity() {
    protected abstract val question: StateFlow<T?>

    protected open val secure: Boolean = false

    /** The window went without an answer while [item] was showing. */
    protected abstract fun onGone(item: T)

    @Composable
    protected abstract fun Sheet(item: T)

    /** The question this window drew last: the one leaving it answers. */
    private var shown: T? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        live.add(javaClass)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                val current by question.collectAsState()
                val now = current
                if (now == null || isFinishing) {
                    LaunchedEffect(Unit) { close() }
                } else {
                    shown = now
                    Sheet(now)
                }
            }
        }
    }

    private fun close() {
        live.remove(javaClass)
        if (!isFinishing) finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            shown?.let(::onGone)
            shown = null
            close()
        }
    }

    override fun onDestroy() {
        if (isFinishing) live.remove(javaClass)
        super.onDestroy()
    }

    companion object {
        /** The kinds of sheet window up and not on their way out: a new question goes to it instead of a second window. */
        private val live: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

        /** Brings [host]'s window up, from any thread (a broker's binder thread); false when Android refused. */
        fun open(context: Context, host: Class<out SheetHostActivity<*>>): Boolean {
            if (host in live) return true
            return runCatching {
                context.startActivity(Intent(context, host).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}

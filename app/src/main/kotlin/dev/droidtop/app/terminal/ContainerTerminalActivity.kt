package dev.droidtop.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.droidtop.app.ContainerRuntimeFactory
import dev.droidtop.app.R
import dev.droidtop.runtime.FailureText
import dev.droidtop.runtime.tasks.OwnScreen
import dev.droidtop.runtime.tasks.OwnScreens
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.PadModality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A terminal into a container on Android, desktop up or not (docs/SPEC.md 3d, "A terminal on Android";
 * Droidtop/tracker#909): Termux's [TerminalView] over a [ContainerShellSession], in droidtop's own look, with the
 * shell's hint row. Opened by Settings > Containers > a container > Terminal (Terminal here while the desktop is up).
 *
 * - Typing: the on-screen keyboard (Hacker Keyboard Reborn or any other) and a hardware keyboard both reach the shell
 *   through the [TerminalView]'s own input connection and key handling, Ctrl, Alt, Esc, Tab and the arrows included.
 * - The pad ([TerminalPad]): the D-pad is the arrow keys, A is Enter, X shows or hides the keyboard, B leaves. The
 *   selection-ring state follows it as everywhere else ([PadModality]).
 * - Turning the screen resizes the terminal, not the activity (configChanges in the manifest): the view's new size
 *   reaches the shell as a window-size change.
 * - The shell belongs to this screen: leaving (B or Back twice while it runs, once after it ended) closes the screen,
 *   and closing the screen ends the shell and what it started ([ContainerShellSession.close]). Nothing runs on in the
 *   background.
 */
class ContainerTerminalActivity : AppCompatActivity() {
    private lateinit var terminal: TerminalView
    private var shell: ContainerShellSession? = null
    private var status by mutableStateOf<String?>(null)
    private var shellRunning by mutableStateOf(false)
    private var leaveArmedAt: Long? = null
    private var textSizePx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(EXTRA_CONTAINER_ID) ?: return finish()
        val name = intent?.getStringExtra(EXTRA_CONTAINER_NAME) ?: id
        val title = getString(R.string.terminal_title, name)
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics).toInt()
        terminal = TerminalView(this, null).apply {
            setTerminalViewClient(viewClient)
            setTextSize(textSizePx)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        status = getString(R.string.terminal_opening)
        onBackPressedDispatcher.addCallback(this) { requestLeave() }
        // On Desktop's bar while it is open (Droidtop/tracker#750), as droidtop's other screens are.
        @Suppress("DEPRECATION")
        OwnScreens.opened(OwnScreen(taskId, title, windowManager.defaultDisplay.displayId))
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
                    Column(Modifier.fillMaxSize().groundBackground()) {
                        Text(
                            title,
                            style = TypeRole.screenTitle,
                            color = MenuTokens.OnSurface,
                            modifier = Modifier.padding(horizontal = Space.Lg, vertical = Space.Sm),
                        )
                        status?.let {
                            Text(
                                it,
                                style = TypeRole.supporting,
                                color = MenuTokens.OnSurfaceMuted,
                                modifier = Modifier.padding(horizontal = Space.Lg),
                            )
                        }
                        AndroidView(
                            factory = { terminal },
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(Space.Sm),
                        )
                        val running = shellRunning
                        HintRow(
                            bindings = listOf(
                                HintBinding(
                                    GamepadAction.A,
                                    stringResource(if (running) R.string.terminal_hint_enter else R.string.terminal_hint_close),
                                ),
                                HintBinding(GamepadAction.X, stringResource(R.string.terminal_hint_keyboard)) { running },
                                HintBinding(GamepadAction.B, stringResource(R.string.terminal_hint_close)),
                            ),
                        )
                    }
                }
            }
        }
        lifecycleScope.launch { open(id) }
    }

    /**
     * Finds the container and its backend's shell off the main thread, then starts the session on it: a Termux
     * session's handler belongs to the thread that makes it, the main one.
     */
    private suspend fun open(id: String) {
        val result = runCatching {
            val launch = withContext(Dispatchers.IO) {
                val runtime = ContainerRuntimeFactory.select(applicationContext)
                val container = runtime.listContainers().firstOrNull { it.container.id == id }?.container
                    ?: error(getString(R.string.terminal_gone))
                runtime.interactiveShell(container)
            }
            ContainerShellSession.start(applicationContext, launch, sessionClient)
        }
        if (isFinishing || isDestroyed) {
            result.getOrNull()?.close()
            return
        }
        result
            .onSuccess { opened ->
                shell = opened
                terminal.attachSession(opened.session)
                shellRunning = true
                status = null
                terminal.requestFocus()
            }
            .onFailure { status = getString(R.string.terminal_failed, FailureText.of(TAG, it)) }
    }

    /** The pad's presses ([TerminalPad]); a keyboard's keys go on to the terminal as typed. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!TerminalPad.isPadKey(event.keyCode, event.source)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            PadModality.padDriving()
            val press = GamepadKeyMap.actionFor(event.keyCode)?.let(TerminalPad::pressFor)
            if (press != null && (event.repeatCount == 0 || TerminalPad.repeats(press))) perform(press)
        }
        return true
    }

    private fun perform(press: TerminalPad.Press) {
        when (press) {
            is TerminalPad.Press.Key -> when {
                !shellRunning && press.keyCode == TerminalPad.KEYCODE_ENTER -> finish()
                shell?.session?.emulator != null -> terminal.handleKeyCode(press.keyCode, 0)
            }
            TerminalPad.Press.ToggleKeyboard -> toggleKeyboard()
            TerminalPad.Press.Leave -> requestLeave()
        }
    }

    /** Leaves at once once the shell has ended; while it runs, the second press within a few seconds leaves. */
    private fun requestLeave() {
        val now = SystemClock.uptimeMillis()
        if (TerminalPad.leaves(shellRunning, leaveArmedAt, now)) {
            finish()
            return
        }
        leaveArmedAt = now
        val asked = getString(R.string.terminal_leave_again)
        status = asked
        lifecycleScope.launch {
            delay(TerminalPad.LEAVE_CONFIRM_MS)
            if (status == asked) status = null
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val shown = ViewCompat.getRootWindowInsets(terminal)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        terminal.requestFocus()
        if (shown) imm.hideSoftInputFromWindow(terminal.windowToken, 0) else imm.showSoftInput(terminal, 0)
    }

    override fun onDestroy() {
        // The session is this screen's: closing the screen ends the shell.
        shell?.close()
        shell = null
        if (!isChangingConfigurations) OwnScreens.closed(taskId)
        super.onDestroy()
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = terminal.onScreenUpdated()
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            shellRunning = false
            status = getString(R.string.terminal_ended)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.terminal_clip_label), text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@ContainerTerminalActivity)
            if (!text.isNullOrEmpty()) session.emulator?.paste(text.toString())
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = terminal.invalidate()
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.w(TAG, "$tag: $message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.w(TAG, tag, e) }
    }

    private val viewClient = object : TerminalViewClient {
        /** Pinching changes the text size a step at a time, within reason. */
        override fun onScale(scale: Float): Float {
            if (scale in 0.9f..1.1f) return scale
            val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_TEXT_SP, resources.displayMetrics).toInt()
            val max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MAX_TEXT_SP, resources.displayMetrics).toInt()
            textSizePx = (textSizePx * if (scale > 1f) 1.1f else 0.9f).toInt().coerceIn(min, max)
            terminal.setTextSize(textSizePx)
            return 1f
        }

        /** A tap is a finger: the selection ring goes, and the keyboard comes up to type with. */
        override fun onSingleTapUp(e: MotionEvent) {
            PadModality.touched()
            terminal.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminal, 0)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) { Log.e(TAG, "$tag: $message") }
        override fun logWarn(tag: String?, message: String?) { Log.w(TAG, "$tag: $message") }
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.w(TAG, "$tag: $message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.w(TAG, tag, e) }
    }

    companion object {
        private const val TAG = "droidtop.Terminal"
        private const val EXTRA_CONTAINER_ID = "dev.droidtop.app.extra.CONTAINER_ID"
        private const val EXTRA_CONTAINER_NAME = "dev.droidtop.app.extra.CONTAINER_NAME"
        private const val TEXT_SP = 14f
        private const val MIN_TEXT_SP = 8f
        private const val MAX_TEXT_SP = 32f

        /** Opens the terminal into container [containerId], called [name] on screen. */
        fun intent(context: Context, containerId: String, name: String): Intent =
            Intent(context, ContainerTerminalActivity::class.java)
                .putExtra(EXTRA_CONTAINER_ID, containerId)
                .putExtra(EXTRA_CONTAINER_NAME, name)
    }
}

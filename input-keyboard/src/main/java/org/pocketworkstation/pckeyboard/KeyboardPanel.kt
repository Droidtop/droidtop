package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Where droidtop's keyboard types (docs/SPEC.md 4c, "Typing on the add-on display", Droidtop/tracker#314).
 * Keys arrive on the hardware model [SecondScreenKeyboardListener] speaks: Android keycodes, pressed and
 * released, Shift as its own key around a capital. [text] carries what no key produces (an emoji from a
 * popup); a destination without a text channel (a compositor) sets [takesText] false.
 */
interface KeyboardSink {
    fun key(androidKeyCode: Int, down: Boolean)

    val takesText: Boolean get() = true

    fun text(chars: CharSequence) = Unit
}

/**
 * droidtop's one keyboard surface: the embedded Hacker's Keyboard grid ([SecondScreenKeyboard.createView], the
 * same `LatinKeyboardView`, layouts and themes droidtop's input method draws) wired to a [KeyboardSink]. Every
 * place droidtop draws a keyboard of its own uses this view: the companion's Input tab and its Keys button, the
 * Social tab's draft, droidtop's own text fields on a screen Android draws no keyboard on, and the keyboard
 * droidtop pops over another app on such a screen. Only the sink differs.
 *
 * [suppressImeView] is for surfaces that type into the input method's editor: while one is on screen, the input
 * method does not also put its own view up ([SecondScreenKeyboard.setAttached]). It follows window visibility,
 * not attachment, because a stopped Activity's views stay attached (Droidtop/tracker#156).
 *
 * [context] must belong to the display the panel is drawn on: the key grid sizes itself from its metrics.
 */
class KeyboardPanel(
    context: Context,
    private val sink: KeyboardSink,
    private val heightPercent: Float = SecondScreenKeyboard.DEFAULT_HEIGHT_PERCENT,
    private val suppressImeView: Boolean = false,
) : FrameLayout(context) {

    private var functionLayer = false
    private val holdHandler = Handler(Looper.getMainLooper())
    private val spaceTimer = object : HoldTimer {
        private var pending: Runnable? = null

        override fun start(delayMs: Long, action: () -> Unit) {
            cancel()
            val run = Runnable { action() }
            pending = run
            holdHandler.postDelayed(run, delayMs)
        }

        override fun cancel() {
            pending?.let { holdHandler.removeCallbacks(it) }
            pending = null
        }
    }
    private val listener = SecondScreenKeyboardListener(
        send = { code, down -> sink.key(code, down) },
        resolver = AndroidCharKeyResolver(),
        commit = if (sink.takesText) ({ chars: CharSequence -> sink.text(chars) }) else null,
        onLayoutToggle = { toggleLayout() },
        deferSpace = ToolsPrefs.spaceDrag(context),
        holdTimer = spaceTimer,
    )
    private val keyboardView: LatinKeyboardView? = run {
        // The key grid reads the form (full, split, one-handed) from the shared settings, which only the input method loads.
        LatinIME.sKeyboardSettings.form = ToolsPrefs.form(context)
        runCatching { SecondScreenKeyboard.createView(context, listener, heightPercent) }.getOrNull()
    }

    // The tool strip (clipboard history, macros, incognito) above the grid, or the grid alone when it is switched off.
    private val deck: View? = keyboardView?.let { grid ->
        ToolsDeck.wrap(
            context,
            grid,
            sink,
            if (sink.takesText) null else ({ chars: CharSequence -> listener.onText(chars) }),
            null,
            Runnable { rebuildKeys() },
        )
    }

    init {
        // Space-bar drag is read by the key tracker from the shared settings, which only the input method loads.
        LatinIME.sKeyboardSettings.spaceDrag = ToolsPrefs.spaceDrag(context)
        // Never a focus target: a tap on a key must leave focus on the field being typed into (and on the screen
        // the user is typing on, for the companion).
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        keyboardView?.let {
            it.isFocusable = false
            it.isFocusableInTouchMode = false
        }
        deck?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    /** Whether the key grid could be built at all (a broken keyboard resource leaves an empty panel). */
    val hasKeys: Boolean get() = keyboardView != null

    /** The key grid again, for a form that changed: the same layer, built with the new shape. */
    private fun rebuildKeys() {
        val view = keyboardView ?: return
        SecondScreenKeyboard.applyLayout(view, context, functionLayer, heightPercent)
    }

    private fun toggleLayout() {
        val view = keyboardView ?: return
        functionLayer = !functionLayer
        SecondScreenKeyboard.applyLayout(view, context, functionLayer, heightPercent)
    }

    private var countedForIme = false

    private fun syncImeSuppression() {
        if (!suppressImeView) return
        val shouldCount = isAttachedToWindow && windowVisibility == View.VISIBLE
        if (shouldCount == countedForIme) return
        countedForIme = shouldCount
        SecondScreenKeyboard.setAttached(shouldCount)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncImeSuppression()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncImeSuppression()
    }

    override fun onDetachedFromWindow() {
        if (countedForIme) {
            countedForIme = false
            SecondScreenKeyboard.setAttached(false)
        }
        // A modifier whose release never arrives leaves the far side holding Ctrl.
        listener.releaseEverything()
        super.onDetachedFromWindow()
    }
}

/**
 * The meta state a hardware-style key stream implies. A synthetic key event has no framework meta tracking
 * behind it, so a capital is only reliably one when the letter's own event carries META_SHIFT_ON. This is the
 * pairing Android itself defines between `KEYCODE_*_LEFT/RIGHT` and `META_*_ON`.
 */
object KeyMeta {
    fun updated(current: Int, keyCode: Int, down: Boolean): Int {
        val bit = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
            else -> return current
        }
        return if (down) current or bit else current and bit.inv()
    }
}

/**
 * Types into the editor droidtop's input method is bound to ([SecondScreenKeyboard.androidTarget]): whatever text
 * field has focus, in any app, on any display, as long as droidtop's keyboard is the selected input method. Each
 * key is an `InputConnection.sendKeyEvent`, whose contract is "as though a hardware key was pressed". This works
 * where Android draws no keyboard window, because the input session does not depend on that window (SPEC 4c).
 * [onNoTarget] runs when a key finds no editor.
 */
class ImeConnectionSink(private val onNoTarget: () -> Unit = {}) : KeyboardSink {
    private var metaState = 0

    override fun key(androidKeyCode: Int, down: Boolean) {
        metaState = KeyMeta.updated(metaState, androidKeyCode, down)
        val connection = SecondScreenKeyboard.androidTarget
        if (connection == null) {
            onNoTarget()
            return
        }
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(
            KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, androidKeyCode, 0, metaState),
        )
    }

    override fun text(chars: CharSequence) {
        SecondScreenKeyboard.androidTarget?.commitText(chars, 1)
    }
}

/**
 * Types into a field of droidtop's OWN window: each key is dispatched into [target] (a view of that window) as a
 * key from Android's virtual keyboard device, the path an attached keyboard's key takes inside that window. No
 * input method and no window focus are involved, so it works on a screen Android draws no keyboard on. Text no
 * key produces is turned into keys by the virtual keyboard's own character map; what it cannot type is dropped.
 */
class WindowKeySink(private val target: () -> View?) : KeyboardSink {
    private var metaState = 0

    override fun key(androidKeyCode: Int, down: Boolean) {
        metaState = KeyMeta.updated(metaState, androidKeyCode, down)
        val view = target() ?: return
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(
            KeyEvent(
                now, now,
                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                androidKeyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    override fun text(chars: CharSequence) {
        val view = target() ?: return
        val events = runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(chars.toString().toCharArray())
        }.getOrNull() ?: return
        events.forEach { view.dispatchKeyEvent(it) }
    }
}

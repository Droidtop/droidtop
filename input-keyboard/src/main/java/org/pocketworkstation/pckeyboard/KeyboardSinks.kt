package org.pocketworkstation.pckeyboard

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View

/**
 * Where droidtop's keyboard types (docs/SPEC.md 4c, "Typing on the add-on display", Droidtop/tracker#314).
 * Keys arrive on the hardware model [SecondScreenKeyboardListener] speaks: Android keycodes, pressed and
 * released, Shift as its own key around a capital. [text] carries what no key produces (an emoji from a
 * popup); a destination without a text channel (a compositor) sets [takesText] false.
 *
 * A held key reaches a hardware keyboard's far side as a stream of repeats the far side makes itself (a compositor
 * does). Any other destination (an input connection, a window, a draft) sees one key-down per press, so the panel
 * repeats Backspace, Delete, the arrows and Page Up and Down for it while they are held, unless the sink says its
 * far side repeats ([repeatsAtSource] false).
 */
interface KeyboardSink {
    fun key(androidKeyCode: Int, down: Boolean)

    val takesText: Boolean get() = true

    /** Whether the panel repeats a held repeatable key (true), or the destination does it itself (false). */
    val repeatsAtSource: Boolean get() = true

    fun text(chars: CharSequence) = Unit
}

/**
 * The meta state one hardware-style key stream implies, for a sink that builds its own key events or reads
 * characters (Droidtop/tracker#772: each sink tracked it by hand). A synthetic key event has no framework meta
 * tracking behind it, so a capital is only reliably one when the letter's own event carries META_SHIFT_ON. Each sink
 * keeps its own tracker: two destinations never share a held Shift.
 */
class MetaTracker {
    /** The `META_*_ON` bits of the modifiers held now. */
    var state = 0
        private set

    /** Folds [keyCode] going [down] or up into [state]; returns whether it was a modifier. */
    fun track(keyCode: Int, down: Boolean): Boolean {
        val bit = bitOf(keyCode)
        if (bit == 0) return false
        state = if (down) state or bit else state and bit.inv()
        return true
    }

    /**
     * The character [keyCode] types under the held Shift on Android's virtual keyboard ([KeyTable.platform]); null
     * for a key that types none, or a dead key.
     */
    fun charOf(keyCode: Int): Char? {
        val unicode = logged("no character for key $keyCode") { KeyTable.platform.get(keyCode, state and KeyEvent.META_SHIFT_ON) }.getOrDefault(0)
        return unicode.takeIf { it != 0 && it and KeyCharacterMap.COMBINING_ACCENT == 0 }?.toChar()
    }

    /**
     * [keyCode] going [down] or up now, with the held modifiers. [softKeyboard] marks it as the virtual keyboard
     * device's key from a soft keyboard, the path an attached keyboard's key takes inside a window.
     */
    fun event(keyCode: Int, down: Boolean, softKeyboard: Boolean = false): KeyEvent {
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        if (!softKeyboard) return KeyEvent(now, now, action, keyCode, 0, state)
        return KeyEvent(
            now, now, action, keyCode, 0, state,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD,
        )
    }

    companion object {
        /** The `META_*_ON` bit a modifier key sets, or 0: the pairing Android defines for `KEYCODE_*_LEFT/RIGHT`. */
        fun bitOf(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
            else -> 0
        }
    }
}

/**
 * Types into the editor droidtop's input method is bound to ([SecondScreenKeyboard.androidTarget]): whatever text
 * field has focus, in any app, on any display, as long as droidtop's keyboard is the selected input method. Each
 * key is an `InputConnection.sendKeyEvent`, whose contract is "as though a hardware key was pressed". This works
 * where Android draws no keyboard window, because the input session does not depend on that window (SPEC 4c).
 * [onNoTarget] runs when a key or text finds no editor.
 */
class ImeConnectionSink(private val onNoTarget: () -> Unit = {}) : KeyboardSink {
    private val meta = MetaTracker()

    override fun key(androidKeyCode: Int, down: Boolean) {
        meta.track(androidKeyCode, down)
        val connection = SecondScreenKeyboard.androidTarget ?: return onNoTarget()
        connection.sendKeyEvent(meta.event(androidKeyCode, down))
    }

    override fun text(chars: CharSequence) {
        val connection = SecondScreenKeyboard.androidTarget ?: return onNoTarget()
        connection.commitText(chars, 1)
    }
}

/**
 * Types into a field of droidtop's OWN window: each key is dispatched into [target] (a view of that window) as a
 * key from Android's virtual keyboard device, the path an attached keyboard's key takes inside that window. No
 * input method and no window focus are involved, so it works on a screen Android draws no keyboard on. Text no
 * key produces is turned into keys by the virtual keyboard's own character map; what it cannot type is dropped.
 */
class WindowKeySink(private val target: () -> View?) : KeyboardSink {
    private val meta = MetaTracker()

    override fun key(androidKeyCode: Int, down: Boolean) {
        meta.track(androidKeyCode, down)
        target()?.dispatchKeyEvent(meta.event(androidKeyCode, down, softKeyboard = true))
    }

    override fun text(chars: CharSequence) {
        val view = target() ?: return
        val events = KeyTable.platform.getEvents(chars.toString().toCharArray()) ?: return
        events.forEach { view.dispatchKeyEvent(it) }
    }
}

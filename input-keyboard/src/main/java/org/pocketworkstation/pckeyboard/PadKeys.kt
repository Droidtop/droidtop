package org.pocketworkstation.pckeyboard

import android.view.InputDevice
import android.view.KeyEvent

/**
 * A pad on the input method's own keyboard (Droidtop/tracker#338; docs/SPEC.md 6a "A pad on the keyboard"). While the
 * keyboard shows, Android hands a key event to the input method before the app, and this takes the pad's: the D-pad
 * moves a focus ring over the keys ([KeyFocus]) and A presses the key under it; the other buttons mean what they mean
 * on droidtop's text-entry slab (SPEC 4c, [Slab.button]), so a pad types the same way everywhere: X Backspace (held,
 * it repeats), Y Space, L1 Shift, R1 the symbols, R2 or Start Enter, B closes the keyboard. The face buttons go through
 * [FaceKeys], so a Nintendo-layout pad's swap holds. A physical keyboard is not taken: it types, and its arrows move
 * the app's caret, as before. The ring shows only while the pad drives and goes with a touch.
 */
internal class PadKeys(private val ime: LatinIME) {
    /** The ring's point, in the keyboard's own coordinates; null while the pad has not driven since the last touch. */
    private var point: Pair<Float, Float>? = null

    /** Pad buttons whose press this took, so their release is taken too and never reaches the app half-pressed. */
    private val held = mutableSetOf<Int>()

    fun clear() {
        point = null
        held.clear()
        view()?.setPadFocus(null)
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!fromPad(event)) return false
        val view = view() ?: return false
        if (!view.isShown) return false
        val keys = view.keyboard?.keys ?: return false
        if (keys.isEmpty()) return false
        // A touch took the ring away (LatinKeyboardBaseView): the pad starts again from the middle.
        if (view.padFocus == null) point = null
        val boxes = keys.map { KeyFocus.Box(it.x, it.y, it.width, it.height) }
        val first = event.repeatCount == 0
        val move = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Slab.Move.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Slab.Move.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Slab.Move.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Slab.Move.RIGHT
            else -> null
        }
        if (move != null) {
            val at = point?.let { KeyFocus.nearest(boxes, it.first, it.second) }
            focus(view, keys, boxes, if (at == null) KeyFocus.start(boxes) else KeyFocus.next(boxes, at, move))
            held += keyCode
            return true
        }
        val role = FaceKeys.roleOf(keyCode)
        when {
            role == FaceKeys.Role.CONFIRM -> if (first) {
                val at = point?.let { KeyFocus.nearest(boxes, it.first, it.second) }
                // The first A shows the ring; the next presses the key under it.
                if (at == null) focus(view, keys, boxes, KeyFocus.start(boxes)) else press(view, keys[at])
            }
            role == FaceKeys.Role.X -> type(Keyboard.KEYCODE_DELETE)
            role == FaceKeys.Role.Y -> if (first) type(' '.code)
            role == FaceKeys.Role.CANCEL -> if (first) {
                clear()
                ime.requestHideSelf(0)
                return true
            }
            keyCode == KeyEvent.KEYCODE_BUTTON_L1 -> if (first) type(Keyboard.KEYCODE_SHIFT)
            keyCode == KeyEvent.KEYCODE_BUTTON_R1 -> if (first) type(Keyboard.KEYCODE_MODE_CHANGE)
            keyCode == KeyEvent.KEYCODE_BUTTON_R2 || keyCode == KeyEvent.KEYCODE_BUTTON_START -> if (first) type('\n'.code)
            else -> return false
        }
        held += keyCode
        refreshLater(view)
        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = fromPad(event) && held.remove(keyCode)

    private fun fromPad(event: KeyEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            KeyEvent.isGamepadButton(event.keyCode)

    private fun view(): LatinKeyboardView? = KeyboardSwitcher.getInstance().inputView

    private fun focus(view: LatinKeyboardView, keys: List<Keyboard.Key>, boxes: List<KeyFocus.Box>, index: Int) {
        if (index !in keys.indices) return
        point = boxes[index].cx to boxes[index].cy
        view.setPadFocus(keys[index])
    }

    /** The key under the ring, pressed as a tap on it presses it. */
    private fun press(view: LatinKeyboardView, key: Keyboard.Key) {
        val text = key.text
        if (text != null) {
            ime.onText(text)
        } else {
            val code = key.codes.firstOrNull() ?: return
            ime.onPress(code)
            ime.onKey(code, key.codes, key.x + key.width / 2, key.y + key.height / 2)
            ime.onRelease(code)
        }
        refreshLater(view)
    }

    private fun type(code: Int) {
        ime.onPress(code)
        ime.onKey(code, intArrayOf(code), LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE, LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE)
        ime.onRelease(code)
    }

    /** A press can change the layer (Shift, symbols): the ring moves to the new layer's key nearest its point. */
    private fun refreshLater(view: LatinKeyboardView) {
        view.post {
            val at = point ?: return@post
            val current = view() ?: return@post
            val keys = current.keyboard?.keys ?: return@post
            val boxes = keys.map { KeyFocus.Box(it.x, it.y, it.width, it.height) }
            val index = KeyFocus.nearest(boxes, at.first, at.second)
            if (index in keys.indices) current.setPadFocus(keys[index])
        }
    }
}

package org.pocketworkstation.pckeyboard

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View

/**
 * A pad on the input method's own keyboard (Droidtop/tracker#338; docs/SPEC.md 6a "A pad on the keyboard"). While the
 * keyboard shows, Android hands a key event to the input method before the app, and this takes the pad's: the D-pad
 * moves a focus ring over the keys ([KeyFocus]) and A presses the key under it; the other buttons mean what they mean
 * on droidtop's text-entry slab (SPEC 4c, [Slab.button]), so a pad types the same way everywhere: X Backspace (held,
 * it repeats), Y Space, L1 Shift, R1 the symbols, R2 or Start Enter, B closes the keyboard. The face buttons go through
 * [FaceKeys], so a Nintendo-layout pad's swap holds. A physical keyboard is not taken: it types, and its arrows move
 * the app's caret, as before. The ring shows only while the pad drives and goes with a touch.
 *
 * Above the keys (Droidtop/tracker#903 H12c): Up from the top row takes the ring onto the tool strip, then the mode's
 * own controls (one-handed's switch side and back to full, floating's Dock); Left and Right step along them, A presses
 * one, Down or B goes back to the keys. A panel a tool opens (the Modes chooser, clipboard, emoji, macros) takes the pad
 * while it is open: every direction steps through its buttons, A presses, B closes it. So every mode is reached by the
 * pad the way it is by touch, through the strip's Modes button, with no chord of its own. In the clipboard panel
 * (Droidtop/tracker#903 H12d) the ring steps through the clips first, then the header's buttons: A pastes the clip,
 * X opens its menu (Paste, Pin or Unpin, Delete), and B closes the menu, or the panel when no menu is open.
 */
internal class PadKeys(private val ime: LatinIME) {
    /** The ring's point, in the keyboard's own coordinates; null while the pad has not driven since the last touch. */
    private var point: Pair<Float, Float>? = null

    /** Pad buttons whose press this took, so their release is taken too and never reaches the app half-pressed. */
    private val held = mutableSetOf<Int>()

    /** The tool above the keys the ring is on, an index into [ToolsDeck.padTargets]; -1 while it is on the keys. */
    private var tool = -1

    /** The tool drawn with the ring, and the foreground it had before. */
    private var lit: View? = null
    private var litWas: Drawable? = null

    fun clear() {
        point = null
        held.clear()
        view()?.setPadFocus(null)
        tool = -1
        light(null)
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
        val deck = ime.toolsDeck
        if (deck != null && (deck.panelOpen || tool >= 0)) {
            if (onTools(deck, view, boxes, keyCode, move, first)) {
                held += keyCode
                return true
            }
        } else if (move != null) {
            val at = point?.let { KeyFocus.nearest(boxes, it.first, it.second) }
            val next = if (at == null) KeyFocus.start(boxes) else KeyFocus.next(boxes, at, move)
            // Up from the top row: onto the tool strip, at the tool nearest across.
            if (move == Slab.Move.UP && at != null && next == at && deck != null && enterTools(deck, view, boxes[at].cx)) {
                held += keyCode
                return true
            }
            focus(view, keys, boxes, next)
            held += keyCode
            return true
        }
        if (FaceKeys.roleOf(keyCode) == FaceKeys.Role.CONFIRM) {
            if (first) {
                val at = point?.let { KeyFocus.nearest(boxes, it.first, it.second) }
                // The first A shows the ring; the next presses the key under it.
                if (at == null) focus(view, keys, boxes, KeyFocus.start(boxes)) else press(view, keys[at])
            }
        } else {
            // The same buttons, read the same way, as on the slab ([Slab.PadButton]); here they become the keyboard's
            // own keys. Select has no job on this keyboard and stays the app's.
            when (Slab.PadButton.of(keyCode)) {
                Slab.PadButton.X -> type(Keyboard.KEYCODE_DELETE)
                Slab.PadButton.Y -> if (first) type(' '.code)
                Slab.PadButton.B -> if (first) {
                    clear()
                    ime.requestHideSelf(0)
                    return true
                }
                Slab.PadButton.L1 -> if (first) type(Keyboard.KEYCODE_SHIFT)
                Slab.PadButton.R1 -> if (first) type(Keyboard.KEYCODE_MODE_CHANGE)
                Slab.PadButton.R2 -> if (first) type('\n'.code)
                Slab.PadButton.SELECT, null -> return false
            }
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

    /** The ring onto the tool above the keys nearest [keyX] (in the keys' coordinates); false when there is none. */
    private fun enterTools(deck: ToolsDeck, view: LatinKeyboardView, keyX: Float): Boolean {
        val targets = deck.padTargets()
        if (targets.isEmpty()) return false
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        val x = origin[0] + keyX
        tool = targets.indices.minByOrNull { i ->
            val at = IntArray(2).also { targets[i].getLocationOnScreen(it) }
            kotlin.math.abs(at[0] + targets[i].width / 2f - x)
        } ?: 0
        view.setPadFocus(null)
        light(targets[tool])
        return true
    }

    /**
     * A pad button while the ring is above the keys, or a panel is open: true when it was taken. Directions step, A
     * presses, Down from the strip and B go back; the other buttons type as they do on the keys.
     */
    private fun onTools(deck: ToolsDeck, view: LatinKeyboardView, boxes: List<KeyFocus.Box>, keyCode: Int, move: Slab.Move?, first: Boolean): Boolean {
        val targets = deck.padTargets()
        val panel = deck.panelOpen
        if (targets.isEmpty() || tool !in targets.indices) tool = 0
        if (targets.isEmpty()) {
            if (!panel) backToKeys(view, boxes, null)
            return false
        }
        when {
            move != null -> when {
                move == Slab.Move.DOWN && !panel -> backToKeys(view, boxes, targets[tool])
                move == Slab.Move.UP && !panel -> Unit
                else -> {
                    val step = if (move == Slab.Move.LEFT || move == Slab.Move.UP) -1 else 1
                    tool = (tool + step + targets.size) % targets.size
                    light(targets[tool])
                }
            }
            FaceKeys.roleOf(keyCode) == FaceKeys.Role.CONFIRM -> if (first) {
                if (lit !== targets[tool]) {
                    light(targets[tool])
                } else {
                    targets[tool].performClick()
                    // What the press opened (a panel) takes the ring at its first button; a mode chosen builds the
                    // keyboard again, and the pad starts again from the keys.
                    view.post { afterPress() }
                }
            }
            // X is the clip's menu in the clipboard panel, never a Backspace into the field behind it.
            Slab.PadButton.of(keyCode) == Slab.PadButton.X && deck.clipboardOpen -> if (first && deck.padMenu(targets[tool])) {
                // The menu takes the ring at its first button.
                view.post { afterPress() }
            }
            Slab.PadButton.of(keyCode) == Slab.PadButton.B -> if (first) {
                if (panel && deck.padBack()) {
                    // A menu inside the panel closed: the ring goes back to the first clip.
                    tool = 0
                    light(deck.padTargets().firstOrNull())
                    return true
                }
                if (panel) deck.hidePanel()
                backToKeys(view, boxes, null)
            }
            else -> return false
        }
        return true
    }

    private fun afterPress() {
        val deck = ime.toolsDeck
        val current = lit
        if (deck == null || current == null || !current.isAttachedToWindow) {
            clear()
            return
        }
        val targets = deck.padTargets()
        tool = if (deck.panelOpen) 0 else targets.indexOf(current)
        if (tool in targets.indices) light(targets[tool]) else clear()
    }

    /** The ring back on the keys, under [from] (a tool) or at the middle. */
    private fun backToKeys(view: LatinKeyboardView, boxes: List<KeyFocus.Box>, from: View?) {
        tool = -1
        light(null)
        val keys = view.keyboard?.keys ?: return
        val index = if (from == null) {
            KeyFocus.start(boxes)
        } else {
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            val at = IntArray(2).also { from.getLocationOnScreen(it) }
            KeyFocus.nearest(boxes, at[0] + from.width / 2f - origin[0], 0f)
        }
        focus(view, keys, boxes, index)
    }

    /** The ring drawn on [target] (over its own look, given back when the ring moves on), scrolled into sight. */
    private fun light(target: View?) {
        lit?.foreground = litWas
        lit = target
        litWas = target?.foreground
        if (target == null) return
        target.foreground = GradientDrawable().apply {
            setStroke((RING_DP * target.resources.displayMetrics.density).toInt(), KeyboardAppearance.colors(target.context).accent)
            cornerRadius = RING_DP * 2 * target.resources.displayMetrics.density
        }
        target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height))
    }

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

    private companion object {
        /** The tool ring's stroke, as wide as the key ring's (LatinKeyboardBaseView). */
        const val RING_DP = 3f
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

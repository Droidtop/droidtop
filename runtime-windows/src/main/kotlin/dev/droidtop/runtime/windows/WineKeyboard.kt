package dev.droidtop.runtime.windows

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Where the soft keyboard lands while a Windows game is on screen.
 *
 * A Wine guest is an X11 client and knows nothing of Android's text input,
 * so what the keyboard produces has to become key presses: text the IME
 * commits is turned into the key events that would have typed it (Shift
 * included, from the key character map), Backspace, Enter and the keys an
 * IME sends directly go the same way, and all of it is handed to
 * [sendKeyEvent], which gives it to the X server's keyboard. A character no
 * key on the map types is dropped: the X keyboard has no key for it either.
 *
 * Adapted from DroidDeck's input/KeyboardHost.kt (GPL-3.0,
 * github.com/Droid-Deck/DroidDeck), which does the same for gamescope.
 * Invisible and unfocusable until asked for: a focused view would take the
 * D-pad and sticks away from the game.
 *
 * Opened by a three-finger swipe up ([com.winlator.widget.TouchpadView]).
 * Back closes it: the activity hides it when it sees Back, and when the IME
 * took Back itself and went away, the window insets say so and [shown]
 * follows, so the next Back is the game's again.
 */
class WineKeyboard(
    context: Context,
    private val sendKeyEvent: (KeyEvent) -> Boolean,
) : View(context) {

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val map: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    var shown = false
        private set

    /** The IME has been on screen since [show]; only then does its going away mean something. */
    private var imeSeen = false

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        layoutParams = FrameLayout.LayoutParams(1, 1)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                imeSeen = true
            } else if (shown && imeSeen) {
                hide()
            }
            insets
        }
    }

    override fun onCheckIsTextEditor(): Boolean = shown

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!shown) return null
        // No suggestions and no autocorrect: each key goes as it was pressed,
        // which is what a game's name or password field needs.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return KeyConnection()
    }

    fun toggle() = if (shown) hide() else show()

    fun show() {
        shown = true
        imeSeen = false
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        shown = false
        imeSeen = false
        imm.hideSoftInputFromWindow(windowToken, 0)
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
    }

    private inner class KeyConnection : BaseInputConnection(this, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) return true
            map.getEvents(text.toString().toCharArray())?.forEach { sendKeyEvent(it) }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = this@WineKeyboard.sendKeyEvent(event)

        override fun performEditorAction(actionCode: Int): Boolean {
            tap(KeyEvent.KEYCODE_ENTER)
            return true
        }

        private fun tap(keyCode: Int) {
            val t = System.currentTimeMillis()
            sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }
}

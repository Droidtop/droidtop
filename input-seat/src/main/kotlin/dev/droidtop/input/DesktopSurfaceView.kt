package dev.droidtop.input

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * The desktop's surface, and an editor the on-screen keyboard can type into (docs/SPEC.md 6b "The on-screen
 * keyboard", Droidtop/tracker#685). A `SurfaceView` is not an editor, so Android's keyboard never came up over the
 * desktop and typing on a console without a keyboard meant a trip to the companion's Keys tab.
 *
 * What the keyboard sends reaches the container through [router]'s own key path, as a keyboard's keys:
 * - text it commits becomes key presses through Android's virtual key map, so it takes the typed-text keyboard;
 * - keys it sends as key events (Enter, Backspace, arrows, Hacker's Keyboard's Ctrl and Esc) go as they are.
 * Nothing is kept here: like a terminal, the editor is only a way in, so the keyboard is asked for no suggestions
 * or composing.
 */
class DesktopSurfaceView(context: Context, private val router: DesktopInputRouter) : SurfaceView(context) {

    private val manager get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /** Whether the on-screen keyboard was asked for; the bar's Keyboard button turns it over. */
    var keyboardWanted: Boolean = false
        private set

    /** Shows the on-screen keyboard over the desktop, or hides it. Main thread. */
    fun toggleKeyboard() {
        keyboardWanted = !keyboardWanted
        if (keyboardWanted) {
            requestFocus()
            manager.restartInput(this)
            manager.showSoftInput(this, 0)
        } else {
            manager.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    /**
     * A text field in the container took or lost the focus (host-bridge's input method, Droidtop/tracker#685): the
     * on-screen keyboard follows, as it does for an Android text field. Not while a hardware keyboard is attached,
     * which already types into the desktop. Main thread.
     */
    fun followTextField(focused: Boolean) {
        val config = resources.configuration
        val hardware = config.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
        if (hardware || focused == keyboardWanted) return
        toggleKeyboard()
    }

    override fun onCheckIsTextEditor(): Boolean = keyboardWanted

    /**
     * Android's keyboard can already be up when the desktop gets the focus back: shown for another field (the search
     * sheet's) or by Android itself (`show_ime_with_hard_keyboard`). It stayed bound to that field, so its taps reached
     * nothing in the container (Droidtop/tracker#685, rig 2638). The input is started again on the desktop each time it
     * or its window gets the focus, so whatever keyboard is up types into the container.
     */
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) manager.restartInput(this)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isFocused) manager.restartInput(this)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A terminal's attributes: no suggestions, no autocorrect, no composing, so each key arrives as it is typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        android.util.Log.d(TAG, "Keyboard input bound to the desktop (asked for: $keyboardWanted)")
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this@DesktopSurfaceView, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            flush()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            flush()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            router.onKeyEvent(event)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // A keyboard deletes by asking the editor; the container only knows keys.
            repeat(beforeLength.coerceAtMost(MAX_DELETE)) { tap(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength.coerceAtMost(MAX_DELETE)) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        /** What the keyboard put in the editor goes to the container as keys, and the editor is empty again. */
        private fun flush() {
            val editable = editable ?: return
            val text = editable.toString()
            editable.clear()
            if (text.isEmpty()) return
            val keys = typedKeys(text)
            android.util.Log.d(TAG, "Keyboard typed ${text.length} characters, ${keys.size} key events")
            for (event in keys) router.onKeyEvent(event)
        }

        private fun tap(code: Int) {
            router.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            router.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private companion object {
        const val TAG = "droidtop.DesktopIme"

        /** A keyboard asking to delete more than this at once is deleting a selection it imagines; cap it. */
        const val MAX_DELETE = 64

        val VIRTUAL: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

        /** [text] as key events of the virtual keyboard, with Shift where a character needs it; unmappable characters are left out. */
        fun typedKeys(text: String): List<KeyEvent> =
            text.flatMap { char -> VIRTUAL.getEvents(charArrayOf(char))?.toList().orEmpty() }
    }
}

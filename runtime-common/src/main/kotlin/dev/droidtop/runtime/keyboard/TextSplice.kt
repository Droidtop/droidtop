package dev.droidtop.runtime.keyboard

import android.view.KeyEvent

/**
 * A text field's content and selection as an accessibility service reads them (docs/SPEC.md 4c, "Typing on the
 * add-on display"). [selStart] and [selEnd] are always inside [text]; equal means a cursor.
 */
data class FieldText(val text: String, val selStart: Int, val selEnd: Int) {
    val min: Int get() = minOf(selStart, selEnd)
    val max: Int get() = maxOf(selStart, selEnd)

    companion object {
        /**
         * From an accessibility node's text and selection. A field showing its hint holds no text; a selection the
         * field does not report (-1) or one outside the text puts the cursor at the end, where typing would go.
         */
        fun of(text: CharSequence?, showingHint: Boolean, selStart: Int, selEnd: Int): FieldText {
            val value = if (showingHint) "" else text?.toString().orEmpty()
            fun inside(i: Int) = i in 0..value.length
            if (!inside(selStart)) return FieldText(value, value.length, value.length)
            return FieldText(value, selStart, if (inside(selEnd)) selEnd else selStart)
        }
    }
}

/** One edit droidtop's keyboard makes to another app's field through accessibility. */
sealed interface TextEdit {
    data class Insert(val text: String) : TextEdit

    data object DeleteBackward : TextEdit

    data object DeleteForward : TextEdit

    data object Left : TextEdit

    data object Right : TextEdit

    data object Home : TextEdit

    data object End : TextEdit

    data object SelectAll : TextEdit
}

/**
 * The splice the accessibility route performs: an accessibility service can only replace a field's whole text, so
 * typing reads the text and selection, applies the edit, and writes both back. Existing text and the cursor are
 * kept; a selection is replaced like any editor replaces it. Steps by code point, never through half a surrogate
 * pair.
 */
object TextSplice {
    /** The field after [edit], or null when it changes nothing. */
    fun apply(field: FieldText, edit: TextEdit): FieldText? {
        val text = field.text
        val selected = field.min != field.max
        return when (edit) {
            is TextEdit.Insert -> {
                if (edit.text.isEmpty()) return null
                val at = field.min + edit.text.length
                FieldText(text.substring(0, field.min) + edit.text + text.substring(field.max), at, at)
            }
            TextEdit.DeleteBackward -> when {
                selected -> removed(field, field.min, field.max)
                field.min == 0 -> null
                else -> removed(field, field.min - stepBack(text, field.min), field.min)
            }
            TextEdit.DeleteForward -> when {
                selected -> removed(field, field.min, field.max)
                field.max >= text.length -> null
                else -> removed(field, field.max, field.max + stepForward(text, field.max))
            }
            TextEdit.Left -> cursor(field, if (selected) field.min else (field.min - stepBack(text, field.min)).coerceAtLeast(0))
            TextEdit.Right -> cursor(field, if (selected) field.max else (field.max + stepForward(text, field.max)).coerceAtMost(text.length))
            TextEdit.Home -> cursor(field, 0)
            TextEdit.End -> cursor(field, text.length)
            TextEdit.SelectAll -> FieldText(text, 0, text.length).takeIf { it != field }
        }
    }

    private fun removed(field: FieldText, from: Int, to: Int): FieldText =
        FieldText(field.text.substring(0, from) + field.text.substring(to), from, from)

    private fun cursor(field: FieldText, at: Int): FieldText? = FieldText(field.text, at, at).takeIf { it != field }

    private fun stepBack(text: String, at: Int): Int =
        if (at >= 2 && Character.isLowSurrogate(text[at - 1]) && Character.isHighSurrogate(text[at - 2])) 2 else if (at >= 1) 1 else 0

    private fun stepForward(text: String, at: Int): Int =
        if (at + 1 < text.length && Character.isHighSurrogate(text[at]) && Character.isLowSurrogate(text[at + 1])) 2 else if (at < text.length) 1 else 0
}

/** What one key of droidtop's keyboard does to a field reached through accessibility. */
sealed interface FieldKey {
    data class Edit(val edit: TextEdit) : FieldKey

    data object Copy : FieldKey

    data object Cut : FieldKey

    data object Paste : FieldKey

    /** The field's own action key (Go, Search, Send, Done): `AccessibilityAction.ACTION_IME_ENTER`. */
    data object ImeEnter : FieldKey
}

object FieldKeys {
    /**
     * The key [keyCode] pressed with [metaState] in a field that is [multiLine], where [char] is what the key types
     * (from the virtual keyboard's character map), or null for a key that does nothing there.
     */
    fun map(keyCode: Int, metaState: Int, char: Char?, multiLine: Boolean): FieldKey? {
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            return when (keyCode) {
                KeyEvent.KEYCODE_A -> FieldKey.Edit(TextEdit.SelectAll)
                KeyEvent.KEYCODE_C -> FieldKey.Copy
                KeyEvent.KEYCODE_X -> FieldKey.Cut
                KeyEvent.KEYCODE_V -> FieldKey.Paste
                else -> null
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> FieldKey.Edit(TextEdit.DeleteBackward)
            KeyEvent.KEYCODE_FORWARD_DEL -> FieldKey.Edit(TextEdit.DeleteForward)
            KeyEvent.KEYCODE_DPAD_LEFT -> FieldKey.Edit(TextEdit.Left)
            KeyEvent.KEYCODE_DPAD_RIGHT -> FieldKey.Edit(TextEdit.Right)
            KeyEvent.KEYCODE_MOVE_HOME -> FieldKey.Edit(TextEdit.Home)
            KeyEvent.KEYCODE_MOVE_END -> FieldKey.Edit(TextEdit.End)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (multiLine) FieldKey.Edit(TextEdit.Insert("\n")) else FieldKey.ImeEnter
            KeyEvent.KEYCODE_TAB -> if (multiLine) FieldKey.Edit(TextEdit.Insert("\t")) else null
            else -> char?.takeIf { it >= ' ' }?.let { FieldKey.Edit(TextEdit.Insert(it.toString())) }
        }
    }
}

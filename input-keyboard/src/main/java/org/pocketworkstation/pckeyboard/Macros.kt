package org.pocketworkstation.pckeyboard

import android.view.KeyEvent

/** One step of a macro: a key with modifiers held around it, or text to type. */
sealed interface MacroStep {
    data class Chord(
        val keyCode: Int,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
        val meta: Boolean = false,
    ) : MacroStep

    data class Text(val text: String) : MacroStep
}

data class Macro(val name: String, val steps: List<MacroStep>)

/**
 * Macro keys (docs/SPEC.md 6a, "Editing helpers", Droidtop/tracker#340): a name and a sequence of keys, such as a
 * tmux prefix then c. One macro per line of the `pref_macros` setting, `name = steps`; blank lines and lines
 * starting with `#` are ignored, and a line with a step that is not understood is skipped whole so a typo never
 * sends half a sequence. Steps are separated by spaces:
 *
 * - `C-b`, `A-x`, `S-Tab`, `M-x`: a key with Ctrl, Alt, Shift or Meta held (they combine: `C-A-Del`);
 * - a key name: Esc, Enter, Tab, Space, Backspace, Del, Ins, Home, End, PgUp, PgDn, Up, Down, Left, Right, F1 to F12;
 * - a single letter, digit or unshifted punctuation key (a capital letter is Shift and the letter);
 * - `"text"`: typed as text, with `\n` Enter, `\t` Tab, `\"` and `\\`.
 */
object MacroParser {
    const val MAX_STEPS = 64
    const val MAX_TEXT = 2000

    private class MacroError(message: String) : Exception(message)

    fun parse(source: String): List<Macro> = source.lineSequence().mapNotNull { parseLine(it) }.toList()

    fun parseLine(line: String): Macro? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return null
        val name = trimmed.substring(0, eq).trim()
        if (name.isEmpty()) return null
        val steps = parseSteps(trimmed.substring(eq + 1)) ?: return null
        return if (steps.isEmpty()) null else Macro(name, steps)
    }

    /** Why [line] is not a macro, or null when it is one, or is blank or a comment. */
    fun problem(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val eq = trimmed.indexOf('=')
        if (eq < 0) return "needs name = steps"
        if (eq == 0 || trimmed.substring(0, eq).isBlank()) return "needs a name before ="
        return try {
            if (steps(trimmed.substring(eq + 1)).isEmpty()) "no steps after =" else null
        } catch (e: MacroError) {
            e.message
        }
    }

    /** One message per line of [source] that is not a macro, each starting with its line number. */
    fun problems(source: String): List<String> =
        source.lineSequence().withIndex().mapNotNull { (index, line) -> problem(line)?.let { "Line ${index + 1}: $it" } }.toList()

    /** The steps in [text], or null when one is not understood or there are too many. */
    fun parseSteps(text: String): List<MacroStep>? = try {
        steps(text)
    } catch (e: MacroError) {
        null
    }

    private fun steps(text: String): List<MacroStep> {
        val steps = ArrayList<MacroStep>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '"') {
                val out = StringBuilder()
                i++
                var closed = false
                while (i < text.length) {
                    val d = text[i]
                    if (d == '\\' && i + 1 < text.length) {
                        val e = text[i + 1]
                        out.append(
                            when (e) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> e
                            },
                        )
                        i += 2
                    } else if (d == '"') {
                        closed = true
                        i++
                        break
                    } else {
                        out.append(d)
                        i++
                    }
                }
                if (!closed) throw MacroError("a quote is not closed")
                if (out.length > MAX_TEXT) throw MacroError("quoted text is longer than $MAX_TEXT")
                if (out.isNotEmpty()) steps += MacroStep.Text(out.toString())
            } else {
                var end = i
                while (end < text.length && !text[end].isWhitespace()) end++
                steps += chord(text.substring(i, end))
                i = end
            }
            if (steps.size > MAX_STEPS) throw MacroError("more than $MAX_STEPS steps")
        }
        return steps
    }

    private fun chord(token: String): MacroStep.Chord {
        var ctrl = false
        var alt = false
        var shift = false
        var meta = false
        var rest = token
        while (rest.length > 2 && rest[1] == '-') {
            when (rest[0]) {
                'C', 'c' -> ctrl = true
                'A', 'a' -> alt = true
                'S', 's' -> shift = true
                'M', 'm' -> meta = true
                else -> throw MacroError("unknown modifier in '$token' (use C-, A-, S- or M-)")
            }
            rest = rest.substring(2)
        }
        NAMED[rest.lowercase()]?.let { return MacroStep.Chord(it, ctrl, alt, shift, meta) }
        if (rest.length != 1) throw MacroError("unknown key '$token'")
        val ch = rest[0]
        val code = charKey(ch.lowercaseChar()) ?: throw MacroError("unknown key '$token'")
        return MacroStep.Chord(code, ctrl, alt, shift || ch.isUpperCase(), meta)
    }

    /** The Android key that types an unshifted [c], or null. Key codes, not a layout: the far side owns the layout. */
    private fun charKey(c: Char): Int? = when (c) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
        '-' -> KeyEvent.KEYCODE_MINUS
        '=' -> KeyEvent.KEYCODE_EQUALS
        '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
        ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
        ';' -> KeyEvent.KEYCODE_SEMICOLON
        '\'' -> KeyEvent.KEYCODE_APOSTROPHE
        ',' -> KeyEvent.KEYCODE_COMMA
        '.' -> KeyEvent.KEYCODE_PERIOD
        '/' -> KeyEvent.KEYCODE_SLASH
        '\\' -> KeyEvent.KEYCODE_BACKSLASH
        '`' -> KeyEvent.KEYCODE_GRAVE
        else -> null
    }

    private val NAMED: Map<String, Int> = buildMap {
        put("esc", KeyEvent.KEYCODE_ESCAPE)
        put("escape", KeyEvent.KEYCODE_ESCAPE)
        put("enter", KeyEvent.KEYCODE_ENTER)
        put("return", KeyEvent.KEYCODE_ENTER)
        put("tab", KeyEvent.KEYCODE_TAB)
        put("space", KeyEvent.KEYCODE_SPACE)
        put("bs", KeyEvent.KEYCODE_DEL)
        put("backspace", KeyEvent.KEYCODE_DEL)
        put("del", KeyEvent.KEYCODE_FORWARD_DEL)
        put("delete", KeyEvent.KEYCODE_FORWARD_DEL)
        put("ins", KeyEvent.KEYCODE_INSERT)
        put("insert", KeyEvent.KEYCODE_INSERT)
        put("home", KeyEvent.KEYCODE_MOVE_HOME)
        put("end", KeyEvent.KEYCODE_MOVE_END)
        put("pgup", KeyEvent.KEYCODE_PAGE_UP)
        put("pageup", KeyEvent.KEYCODE_PAGE_UP)
        put("pgdn", KeyEvent.KEYCODE_PAGE_DOWN)
        put("pagedown", KeyEvent.KEYCODE_PAGE_DOWN)
        put("up", KeyEvent.KEYCODE_DPAD_UP)
        put("down", KeyEvent.KEYCODE_DPAD_DOWN)
        put("left", KeyEvent.KEYCODE_DPAD_LEFT)
        put("right", KeyEvent.KEYCODE_DPAD_RIGHT)
        for (n in 1..12) put("f$n", KeyEvent.KEYCODE_F1 + (n - 1))
    }
}

/**
 * Plays a macro as a hardware keyboard would: modifiers pressed, the key pressed and released, modifiers released.
 * [key] is the destination's key stream (Android keycodes: [KeyboardSink.key] on the input method's connection, the
 * companion's panel and the container alike), [type] its way to enter text. Keys in a step are never left held.
 */
class MacroPlayer(private val key: (androidKeyCode: Int, down: Boolean) -> Unit, private val type: (CharSequence) -> Unit) {
    fun play(macro: Macro) {
        for (step in macro.steps) {
            when (step) {
                is MacroStep.Chord -> chord(step)
                is MacroStep.Text -> text(step.text)
            }
        }
    }

    private fun chord(step: MacroStep.Chord) {
        val held = buildList {
            if (step.ctrl) add(KeyEvent.KEYCODE_CTRL_LEFT)
            if (step.alt) add(KeyEvent.KEYCODE_ALT_LEFT)
            if (step.shift) add(KeyEvent.KEYCODE_SHIFT_LEFT)
            if (step.meta) add(KeyEvent.KEYCODE_META_LEFT)
        }
        held.forEach { key(it, true) }
        key(step.keyCode, true)
        key(step.keyCode, false)
        held.asReversed().forEach { key(it, false) }
    }

    /** Text goes through [type]; a newline or tab inside it is the Enter or Tab key. */
    private fun text(text: String) {
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) type(run.toString())
            run.setLength(0)
        }
        for (c in text) {
            when (c) {
                '\n' -> {
                    flush()
                    chord(MacroStep.Chord(KeyEvent.KEYCODE_ENTER))
                }
                '\t' -> {
                    flush()
                    chord(MacroStep.Chord(KeyEvent.KEYCODE_TAB))
                }
                else -> run.append(c)
            }
        }
        flush()
    }
}

package org.pocketworkstation.pckeyboard

import android.view.KeyEvent

/**
 * The pad-driven keyboard slab's model (docs/SPEC.md 4c, "The text-entry slab"; Droidtop/tracker#485 S7, slice 6):
 * its layers, its keys, where the pad's cursor is, and what each press sends. Pure: no view and no Android beyond
 * key-code constants, so every rule here is a unit test. [SlabView] draws it; [KeyboardPanel] hosts it.
 *
 * The face-button map (X Backspace, Y Space, L1 Shift with a double press for Caps, R1 the symbols layer, R2 Enter,
 * B Done or Cancel, Select the clipboard) is the S7 design's own. Valve documents none for the Deck's keyboard, so it
 * is marked "to compare with a Deck" in the design and changes here if a Deck shows otherwise (#485, 2026-10-16).
 */
object Slab {
    /** What a key does. [Char] types its character, shifted by the state when it is a letter. */
    sealed interface Action {
        data class Char(val lower: kotlin.Char, val upper: kotlin.Char = lower.uppercaseChar()) : Action
        data object Backspace : Action
        data object Space : Action
        data object Enter : Action
        data object Shift : Action
        data object Symbols : Action
        data object Letters : Action
        data object Done : Action
        data object Clipboard : Action
        data object SystemKeyboard : Action
        data object CaretLeft : Action
        data object CaretRight : Action
        data object Tab : Action
        data object Escape : Action

        /** The extras (a whole keyboard's keys, for the companion's Input tab and Desktop): one-shot modifiers. */
        data object Ctrl : Action
        data object Alt : Action
        data object Super : Action
        data object ArrowUp : Action
        data object ArrowDown : Action
        data class Function(val n: Int) : Action
    }

    /** The pad button a key's keycap shows, when one does that key's job; the host draws the glyph its layout prints. */
    enum class Cap { X, Y, L1, R1, R2, B, SELECT }

    /** One key: its [action], its [width] in letter-key units, and the pad button that also does it. */
    data class Key(val action: Action, val width: Float = 1f, val cap: Cap? = null)

    enum class Layer { LETTERS, SYMBOLS, EMAIL }

    enum class ShiftState { OFF, ONCE, CAPS }

    /** What Enter says on its key and keycap: the purpose of the entry, never "Enter". */
    enum class EnterPurpose(val label: String) { SEARCH("Search"), SAVE("Save"), DONE("Done"), NEXT("Next"), GO("Go"), ENTER("Enter") }

    /** What the entry is for, which picks the first layer (a number opens on symbols, an email on the email layer). */
    enum class Kind { PLAIN, SEARCH, URL, NUMBER, EMAIL }

    /** What B does: on a Field it keeps the text and closes (Done); on a Prompt it cancels and restores. */
    enum class Form { FIELD, PROMPT }

    private fun chars(text: String) = text.map { Key(Action.Char(it)) }

    @Suppress("UNUSED_PARAMETER")
    private fun bottomRow(form: Form) = listOf(
        Key(Action.Symbols, 1.5f, Cap.R1),
        Key(Action.Clipboard, 1.5f, Cap.SELECT),
        Key(Action.Space, 4f, Cap.Y),
        Key(Action.SystemKeyboard, 1.5f),
        Key(Action.CaretLeft),
        Key(Action.CaretRight),
        // Done on a Field, Cancel on a Prompt: one key, labelled by the form (State.label).
        Key(Action.Done, 1.5f, Cap.B),
    )

    /** The extras' row above the letters: what a whole keyboard has and a text field does not need. */
    private fun extrasRow() = listOf(
        Key(Action.Escape), Key(Action.Tab), Key(Action.Ctrl), Key(Action.Alt), Key(Action.Super),
    )

    /**
     * A whole keyboard's bottom row: no Clipboard, globe or Done, which belong to a text field that can close, and the
     * four arrows together. Nothing on the Input tab closes (Droidtop/tracker#567).
     */
    private fun extrasBottomRow(first: Key) = listOf(
        first,
        Key(Action.Space, 5f, Cap.Y),
        Key(Action.CaretLeft),
        Key(Action.ArrowUp),
        Key(Action.ArrowDown),
        Key(Action.CaretRight),
    )

    /**
     * The rows of [layer]. [large] is the Large keys form (automatic at font scale 1.3): four letter rows, the digits on
     * the symbols layer, as the S7 layout has it (5.2).
     * [extras] adds a whole keyboard's keys for the companion's Input tab and Desktop (Droidtop/tracker#567): Esc, Tab,
     * Ctrl, Alt, Super and the up and down arrows above the letters, and F1 to F12 above the symbols.
     */
    fun rows(layer: Layer, large: Boolean, form: Form = Form.FIELD, extras: Boolean = false): List<List<Key>> {
        val base = baseRows(layer, large, form)
        if (!extras) return base
        val whole = base.dropLast(1) + listOf(extrasBottomRow(base.last().first()))
        return when (layer) {
            Layer.SYMBOLS -> listOf((1..12).map { Key(Action.Function(it)) }) + whole
            else -> listOf(extrasRow()) + whole
        }
    }

    private fun baseRows(layer: Layer, large: Boolean, form: Form): List<List<Key>> {
        val backspace = Key(Action.Backspace, 1.5f, Cap.X)
        val enter = Key(Action.Enter, 1.75f, Cap.R2)
        val shift = Key(Action.Shift, 1.5f, Cap.L1)
        return when (layer) {
            Layer.LETTERS, Layer.EMAIL -> buildList {
                if (!large) add(chars("1234567890") + backspace)
                add(chars("qwertyuiop") + listOfNotNull(backspace.takeIf { large }))
                add(chars("asdfghjkl") + enter)
                add(listOf(shift) + chars("zxcvbnm") + if (layer == Layer.EMAIL) chars("@.") else chars(",."))
                add(bottomRow(form))
            }
            Layer.SYMBOLS -> buildList {
                add(chars("1234567890") + backspace)
                add(chars("!@#\$%^&*()"))
                add(chars("-_=+[]{}\\|") + enter)
                add(listOf(Key(Action.Tab, 1.5f)) + chars(";:'\"`~<>?/"))
                add(listOf(Key(Action.Letters, 1.5f, Cap.R1), Key(Action.Escape, 1.5f)) + bottomRow(form).drop(1))
            }
        }
    }

    /** The layer an entry of [kind] opens on. */
    fun firstLayer(kind: Kind): Layer = when (kind) {
        Kind.NUMBER -> Layer.SYMBOLS
        Kind.EMAIL -> Layer.EMAIL
        else -> Layer.LETTERS
    }

    /** What a press produces for the host to deliver. */
    sealed interface Output {
        /** Text to type, through the sink's key or text channel. */
        data class Type(val text: String) : Output

        /** An Android key to send down and up (Backspace, Enter, Tab, Escape, the caret's arrows). */
        data class Press(val keyCode: Int) : Output

        /** A key under held modifiers (Ctrl+C, Alt+Tab): [modifiers] down, the key down and up, the modifiers up. */
        data class Chord(val keyCode: Int, val modifiers: List<Int>) : Output

        /**
         * A whole keyboard's key held under a finger: [modifiers] then the key go down on touch ([Down]), and come up in
         * reverse on release ([Up]); a repeat is another key-down. The host keeps the pair.
         */
        data class Hold(val keyCode: Int, val modifiers: List<Int>)

        data class Down(val hold: Hold, val repeat: Boolean = false) : Output
        data class Up(val hold: Hold) : Output

        /** B: keep the text and close (Field) or cancel (Prompt), the host decides by its [Form]. */
        data object Close : Output
        data object OpenClipboard : Output
        data object UseSystemKeyboard : Output
    }

    /** The slab's state: the layer, Shift, and the pad's cursor (row and key index). */
    data class State(
        val layer: Layer = Layer.LETTERS,
        val shift: ShiftState = ShiftState.OFF,
        val row: Int = 0,
        val col: Int = 0,
        val large: Boolean = false,
        val form: Form = Form.FIELD,
        /** When Shift was last pressed (ms), for the double press that sets Caps. */
        val lastShiftAt: Long = Long.MIN_VALUE / 2,
        val extras: Boolean = false,
        /** The extras' modifiers pressed for the next key (Ctrl, Alt, Super), one shot each. */
        val modifiers: Set<Action> = emptySet(),
    ) {
        val keys: List<List<Key>> get() = rows(layer, large, form, extras)
        val focused: Key? get() = keys.getOrNull(row)?.getOrNull(col)

        /** The label a key shows now: a letter follows Shift, everything else is its fixed name. */
        fun label(key: Key, enter: EnterPurpose): String = when (val a = key.action) {
            is Action.Char -> if (shift != ShiftState.OFF) a.upper.toString() else a.lower.toString()
            Action.Backspace -> "Backspace"
            Action.Space -> "Space"
            Action.Enter -> enter.label
            Action.Shift -> if (shift == ShiftState.CAPS) "Caps" else "Shift"
            Action.Symbols -> "&123"
            Action.Letters -> "abc"
            Action.Done -> if (form == Form.PROMPT) "Cancel" else "Done"
            Action.Clipboard -> "Clipboard"
            Action.SystemKeyboard -> "System keyboard"
            Action.CaretLeft -> "←"
            Action.CaretRight -> "→"
            Action.Tab -> "Tab"
            Action.Escape -> "Esc"
            Action.Ctrl -> "Ctrl"
            Action.Alt -> "Alt"
            Action.Super -> "Super"
            Action.ArrowUp -> "\u2191"
            Action.ArrowDown -> "\u2193"
            is Action.Function -> "F${a.n}"
        }

        /** Whether a modifier key is pressed for the next key, so the slab draws it lit. */
        fun latched(key: Key): Boolean = key.action in modifiers || (key.action == Action.Shift && shift != ShiftState.OFF)
    }

    /** Where the slab opens: the first key with [lower] on the first layer of [kind], else the top-left key. */
    fun open(kind: Kind, large: Boolean, form: Form, lower: Char = 'g', extras: Boolean = false): State {
        val base = State(layer = firstLayer(kind), large = large, form = form, extras = extras)
        base.keys.forEachIndexed { r, row ->
            row.forEachIndexed { c, key -> if ((key.action as? Action.Char)?.lower == lower) return base.copy(row = r, col = c) }
        }
        return base
    }

    enum class Move { UP, DOWN, LEFT, RIGHT }

    /**
     * The cursor moved one key: left and right wrap within the row, up and down stop at the edges and land on the key
     * whose centre is nearest the current one's, so a wide key above or below is never skipped. Up from the top row is
     * the host's (it moves into the plate's controls): this returns null then.
     */
    fun move(state: State, move: Move): State? {
        val rows = state.keys
        val row = rows[state.row]
        return when (move) {
            Move.LEFT -> state.copy(col = (state.col - 1 + row.size) % row.size)
            Move.RIGHT -> state.copy(col = (state.col + 1) % row.size)
            Move.UP -> if (state.row == 0) null else state.copy(row = state.row - 1, col = nearest(rows, state.row, state.col, state.row - 1))
            Move.DOWN -> if (state.row == rows.lastIndex) state else state.copy(row = state.row + 1, col = nearest(rows, state.row, state.col, state.row + 1))
        }
    }

    private fun centre(row: List<Key>, col: Int): Float {
        val total = row.sumOf { it.width.toDouble() }.toFloat()
        val before = row.take(col).sumOf { it.width.toDouble() }.toFloat()
        return (before + row[col].width / 2f) / total
    }

    private fun nearest(rows: List<List<Key>>, fromRow: Int, fromCol: Int, toRow: Int): Int {
        val x = centre(rows[fromRow], fromCol)
        return rows[toRow].indices.minByOrNull { kotlin.math.abs(centre(rows[toRow], it) - x) } ?: 0
    }

    /** A pad button the slab handles itself (the D-pad and A are [move] and [press] on the focused key). */
    enum class Button { X, Y, L1, R1, R2, B, SELECT }

    /** What [button] does: the key whose keycap it is, pressed. */
    fun button(state: State, button: Button, now: Long): Pair<State, Output?> = press(
        state,
        when (button) {
            Button.X -> Action.Backspace
            Button.Y -> Action.Space
            Button.L1 -> Action.Shift
            Button.R1 -> if (state.layer == Layer.SYMBOLS) Action.Letters else Action.Symbols
            Button.R2 -> Action.Enter
            Button.B -> Action.Done
            Button.SELECT -> Action.Clipboard
        },
        now,
    )

    /** Pressing a key whose action is [action] (A on the focused key, or its pad button). */
    fun press(state: State, action: Action, now: Long): Pair<State, Output?> {
        if (action in MODIFIERS) {
            val next = if (action in state.modifiers) state.modifiers - action else state.modifiers + action
            return state.copy(modifiers = next) to null
        }
        if (state.modifiers.isNotEmpty()) chord(state, action)?.let { return it }
        return plain(state, action, now)
    }

    private val MODIFIERS = setOf(Action.Ctrl, Action.Alt, Action.Super)

    private fun modifierCode(action: Action) = when (action) {
        Action.Ctrl -> KeyEvent.KEYCODE_CTRL_LEFT
        Action.Alt -> KeyEvent.KEYCODE_ALT_LEFT
        else -> KeyEvent.KEYCODE_META_LEFT
    }

    /** A key under the latched modifiers: one chord, after which the modifiers (and a one-shot Shift) let go. */
    private fun chord(state: State, action: Action): Pair<State, Output?>? {
        val held = held(state, action) ?: return null
        return released(state) to Output.Chord(held.keyCode, held.modifiers)
    }

    /**
     * The key a whole keyboard sends while [action]'s key is held (Droidtop/tracker#567: a PC game moves while an arrow
     * or a letter is held), with what is latched held under it: Ctrl, Alt and Super, and Shift for an upper-case
     * character or a named key while Shift is on. Null for a key that is not a key press (Shift, a modifier, a layer
     * switch), which acts on release through [press].
     */
    fun held(state: State, action: Action): Output.Hold? {
        if (action in MODIFIERS) return null
        val (code, shifted) = when (action) {
            is Action.Char -> {
                val ch = if (state.shift != ShiftState.OFF) action.upper else action.lower
                SlabSink.UsKeys.of(ch) ?: return null
            }
            else -> (keyCodeOf(action) ?: return null) to false
        }
        val mods = state.modifiers.sortedBy { modifierCode(it) }.map(::modifierCode)
        val withShift = if (shifted || (state.shift != ShiftState.OFF && action !is Action.Char)) mods + KeyEvent.KEYCODE_SHIFT_LEFT else mods
        return Output.Hold(code, withShift)
    }

    /** After a held key or a chord: the one-shot modifiers let go, and a one-shot Shift goes off; Caps stays. */
    fun released(state: State): State =
        state.copy(modifiers = emptySet(), shift = if (state.shift == ShiftState.ONCE) ShiftState.OFF else state.shift)

    /** Whether a held key repeats (400 ms, then every 50 ms) for a destination that does not repeat it itself. */
    fun repeats(action: Action): Boolean = action == Action.Backspace || action == Action.Space || action is Action.Char ||
        action == Action.CaretLeft || action == Action.CaretRight || action == Action.ArrowUp || action == Action.ArrowDown

    private fun keyCodeOf(action: Action): Int? = when (action) {
        Action.Space -> KeyEvent.KEYCODE_SPACE
        Action.Backspace -> KeyEvent.KEYCODE_DEL
        Action.Enter -> KeyEvent.KEYCODE_ENTER
        Action.Tab -> KeyEvent.KEYCODE_TAB
        Action.Escape -> KeyEvent.KEYCODE_ESCAPE
        Action.CaretLeft -> KeyEvent.KEYCODE_DPAD_LEFT
        Action.CaretRight -> KeyEvent.KEYCODE_DPAD_RIGHT
        Action.ArrowUp -> KeyEvent.KEYCODE_DPAD_UP
        Action.ArrowDown -> KeyEvent.KEYCODE_DPAD_DOWN
        is Action.Function -> KeyEvent.KEYCODE_F1 + action.n - 1
        else -> null
    }

    private fun plain(state: State, action: Action, now: Long): Pair<State, Output?> = when (action) {
        is Action.Char -> {
            val ch = if (state.shift != ShiftState.OFF) action.upper else action.lower
            val next = if (state.shift == ShiftState.ONCE) state.copy(shift = ShiftState.OFF) else state
            next to Output.Type(ch.toString())
        }
        Action.Space -> state to Output.Type(" ")
        Action.Backspace -> state to Output.Press(KeyEvent.KEYCODE_DEL)
        Action.Enter -> state to Output.Press(KeyEvent.KEYCODE_ENTER)
        Action.Tab -> state to Output.Press(KeyEvent.KEYCODE_TAB)
        Action.Escape -> state to Output.Press(KeyEvent.KEYCODE_ESCAPE)
        Action.CaretLeft, Action.CaretRight, Action.ArrowUp, Action.ArrowDown, is Action.Function ->
            state to Output.Press(keyCodeOf(action)!!)
        Action.Shift -> {
            val double = now - state.lastShiftAt <= DOUBLE_PRESS_MS
            val shift = when {
                state.shift == ShiftState.CAPS -> ShiftState.OFF
                double && state.shift == ShiftState.ONCE -> ShiftState.CAPS
                state.shift == ShiftState.ONCE -> ShiftState.OFF
                else -> ShiftState.ONCE
            }
            state.copy(shift = shift, lastShiftAt = now) to null
        }
        Action.Symbols -> switchLayer(state, Layer.SYMBOLS) to null
        Action.Letters -> switchLayer(state, Layer.LETTERS) to null
        Action.Done -> state to Output.Close
        Action.Clipboard -> state to Output.OpenClipboard
        Action.SystemKeyboard -> state to Output.UseSystemKeyboard
        Action.Ctrl, Action.Alt, Action.Super -> state to null
    }

    /** Another layer keeps the cursor on the same row, at the nearest key, so a switch never throws it to a corner. */
    private fun switchLayer(state: State, layer: Layer): State {
        val target = state.copy(layer = layer)
        val rows = target.keys
        val row = state.row.coerceAtMost(rows.lastIndex)
        val x = centre(state.keys[state.row], state.col)
        val col = rows[row].indices.minByOrNull { kotlin.math.abs(centre(rows[row], it) - x) } ?: 0
        return target.copy(row = row, col = col)
    }

    /** Two Shift presses within this many ms set Caps (the S7 design's; to compare with a Deck). */
    const val DOUBLE_PRESS_MS = 400L

    /** Font scale at which Large keys turn on by themselves (S7 5.2). */
    const val LARGE_KEYS_FONT_SCALE = 1.3f
}

/**
 * The slab's output delivered into a [KeyboardSink] (the companion's docked slab, any host whose field is reached
 * through a sink): text through the sink's text channel, keys as a down and an up. [onClose] is B or Done, and the
 * clipboard and the globe key go to the host ([onOther]). A sink without a text channel (a compositor) gets each
 * character as its key where the US layout has one, Shift around a capital or a shifted symbol.
 */
object SlabSink {
    fun deliver(output: Slab.Output, sink: KeyboardSink, onClose: () -> Unit, onOther: (Slab.Output) -> Unit = {}) {
        when (output) {
            is Slab.Output.Type -> if (sink.takesText) sink.text(output.text) else output.text.forEach { typeAsKeys(it, sink) }
            is Slab.Output.Press -> {
                sink.key(output.keyCode, true)
                sink.key(output.keyCode, false)
            }
            is Slab.Output.Chord -> {
                output.modifiers.forEach { sink.key(it, true) }
                sink.key(output.keyCode, true)
                sink.key(output.keyCode, false)
                output.modifiers.asReversed().forEach { sink.key(it, false) }
            }
            is Slab.Output.Down -> {
                if (!output.repeat) output.hold.modifiers.forEach { sink.key(it, true) }
                sink.key(output.hold.keyCode, true)
            }
            is Slab.Output.Up -> {
                sink.key(output.hold.keyCode, false)
                output.hold.modifiers.asReversed().forEach { sink.key(it, false) }
            }
            Slab.Output.Close -> onClose()
            else -> onOther(output)
        }
    }

    private fun typeAsKeys(ch: Char, sink: KeyboardSink) {
        val (code, shifted) = UsKeys.of(ch) ?: return
        if (shifted) sink.key(KeyEvent.KEYCODE_SHIFT_LEFT, true)
        sink.key(code, true)
        sink.key(code, false)
        if (shifted) sink.key(KeyEvent.KEYCODE_SHIFT_LEFT, false)
    }

    /** The US layout's key for a character, and whether Shift is held for it. */
    internal object UsKeys {
        private const val UNSHIFTED = "`1234567890-=[]\\;',./"
        private const val SHIFTED = "~!@#\$%^&*()_+{}|:\"<>?"
        private val CODES = intArrayOf(
            KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_LEFT_BRACKET,
            KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
        )

        fun of(ch: Char): Pair<Int, Boolean>? = when {
            ch in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a') to false
            ch in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A') to true
            ch == ' ' -> KeyEvent.KEYCODE_SPACE to false
            ch == '\t' -> KeyEvent.KEYCODE_TAB to false
            ch in UNSHIFTED -> CODES[UNSHIFTED.indexOf(ch)] to false
            // The shifted characters pair with the unshifted ones position for position (~ on `, ! on 1, ...).
            ch in SHIFTED -> CODES[SHIFTED.indexOf(ch)] to true
            else -> null
        }
    }
}

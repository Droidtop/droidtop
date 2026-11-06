package org.pocketworkstation.pckeyboard

/**
 * The input method's side of the engine seam for an engine other than Hacker's Latin one (Droidtop/tracker#903 H6 and
 * H7a; docs/SPEC.md 6a, "The engine seam"): keys go to [engine], and after each one, and each pick, the session reads
 * its composing text and candidates again and writes them through [editor]: the composing text to the field with the
 * caret where the engine has it, the candidates to the candidate bar's model ([bar]). LatinIME makes one when
 * [InputEngines] gives an engine for the language (H7 to H9: Mozc, librime, libhangul); with none, Hacker's own path
 * runs as it always has and no session exists.
 *
 * While the engine is not [InputEngine.State.Ready] (its library or data still loading, or missing) it is given no
 * keys: they type as plain text, and the strip shows [gettingReady] or the engine's reason instead of candidates.
 *
 * Every call is on the main thread, as Mozc's synchronous evalCommand would be (docs/SPEC.md 6a gives the budget).
 * Pure: the [Editor] is the field and the bar, so a fake engine's whole round trip is tested on the JVM.
 */
class EngineSession @JvmOverloads constructor(
    val engine: InputEngine,
    private val editor: Editor,
    private val gettingReady: CharSequence = "",
    /** The same line with the engine's [InputEngine.progress] in it (#903 H7c): "Japanese is getting ready… 40%". */
    private val gettingReadyAt: Progress = Progress { gettingReady },
) {
    /** The strip's line while the engine loads, for a progress of 0 to 100. */
    fun interface Progress {
        fun line(percent: Int): CharSequence
    }

    /** Where the session writes: the field's composing region and text, and the candidate bar. */
    interface Editor {
        /** Shows [composing] as the field's composing text with the caret at its [InputEngine.Composing.caret] (an empty one removes it). */
        fun setComposing(composing: InputEngine.Composing)

        /** Puts [text] in the field in place of the composing text. */
        fun commit(text: CharSequence)

        /** Deletes [count] chars just before the composing text, or before the caret when nothing is composed. */
        fun deleteBefore(count: Int)

        /** Leaves whatever the composing text shows in the field as typed. */
        fun finishComposing()

        /** Up to [max] chars of the field just before the caret (or the composing text); empty when it cannot say. */
        fun textBeforeCaret(max: Int): CharSequence

        /** The bar's model changed. */
        fun showCandidates(bar: CandidateBar)
    }

    companion object {
        /** How much of the field before the caret an engine is given ([InputEngine.textBeforeCaret]). */
        const val CONTEXT_CHARS = 20

        /** The keys that move about or cancel what is composed: the engine's only while it composes. */
        private val COMPOSING_ONLY = setOf(
            InputEngine.Key.LEFT, InputEngine.Key.RIGHT, InputEngine.Key.UP, InputEngine.Key.DOWN,
            InputEngine.Key.SHIFT_LEFT, InputEngine.Key.SHIFT_RIGHT, InputEngine.Key.ESCAPE,
        )
    }

    val bar = CandidateBar()

    init {
        engine.watchState { refresh() }
    }

    /** Whether the engine can take keys now. */
    val ready: Boolean get() = engine.state == InputEngine.State.Ready

    /** Whether something is composed now. */
    val composing: Boolean get() = !engine.composing().isEmpty

    private val takesKeys: Boolean get() = ready && engine.composes()

    /** A new field: the engine is told, and given the text before the caret. */
    fun startField(suggestionsAllowed: Boolean, password: Boolean) {
        engine.startField(suggestionsAllowed, password)
        if (takesKeys) engine.textBeforeCaret(editor.textBeforeCaret(CONTEXT_CHARS))
        refresh()
    }

    /** The field ended: what was shown stays, and the engine saves what it learned. */
    fun finishField() {
        leave()
        engine.finishField()
    }

    /** Forgets what the engine learned (for a setting). */
    fun forgetLearned() {
        leave()
        engine.forgetLearned()
    }

    /** A character key: true when the engine took it. */
    fun onChar(code: Int, nearby: IntArray?): Boolean = takesKeys && apply(engine.onChar(code, nearby))

    /** Backspace: true when it took a character from what is composed. */
    fun onDelete(): Boolean {
        if (!takesKeys || !engine.onDelete()) return false
        refresh()
        return true
    }

    /**
     * An engine key: true when the engine took it. Space, enter, undo and toggle go to it whenever it is ready; the
     * arrows, Shift with them and Escape only while it composes, and otherwise do what they always did.
     */
    fun onKey(key: InputEngine.Key): Boolean {
        if (!takesKeys) return false
        if (key in COMPOSING_ONLY && !composing) return false
        return apply(engine.onKey(key))
    }

    /** Candidate [index] picked from the bar, by touch or pad: what is composed, or a suggestion while nothing is. */
    fun pick(index: Int): Boolean {
        if (!ready) return false
        val picked = engine.pick(index) ?: return false
        put(picked.deleteBefore, picked.committed)
        refresh()
        return true
    }

    /** The engine's next composition mode, as the strip's mode button cycles them; false when it has fewer than two. */
    fun cycleMode(): Boolean {
        if (!ready) return false
        val modes = engine.modes()
        if (modes.size < 2) return false
        val at = modes.indexOf(engine.mode())
        engine.setMode(modes[(at + 1) % modes.size])
        refresh()
        return true
    }

    /**
     * What is composed goes into the field as it stands (another key, a tool, closing the keyboard), and the engine's
     * suggestions go: they were for the text before that key.
     */
    fun commit() {
        val had = composing
        if (had) {
            val text = engine.commit()
            if (text.isNotEmpty()) put(0, text) else editor.finishComposing()
        }
        if (had || !bar.items.isEmpty()) {
            engine.reset()
            refresh()
        }
    }

    /** The field changed under the engine (the cursor moved away, a new field): what is shown stays, the engine forgets it. */
    fun leave() {
        val had = composing
        engine.reset()
        if (had) editor.finishComposing()
        drawBar()
    }

    /** The session ends (another engine, another language): the engine no longer calls back. */
    fun close() {
        leave()
        engine.watchState(null)
    }

    /** The engine's state, drawn again: read after every key and every pick. */
    fun refresh() {
        if (ready) {
            editor.setComposing(engine.composing())
        } else if (composing) {
            // Its data went away mid-word: what it showed stays as typed.
            engine.reset()
            editor.finishComposing()
        }
        drawBar()
    }

    private fun drawBar() {
        when (val state = engine.state) {
            InputEngine.State.Ready -> {
                bar.show(engine.candidates(), expandable = InputEngine.Capability.CONVERTS in engine.capabilities)
                bar.showMode(engine.mode()?.label)
            }
            is InputEngine.State.Unavailable -> bar.showNotice(state.reason)
            InputEngine.State.Loading -> {
                val percent = engine.progress
                bar.showNotice(if (percent in 0..100) gettingReadyAt.line(percent) else gettingReady)
            }
        }
        editor.showCandidates(bar)
    }

    private fun apply(result: InputEngine.KeyResult): Boolean {
        if (!result.handled) return false
        put(result.deleteBefore, result.committed)
        refresh()
        return true
    }

    /** Deletes before, then commits, as Mozc applies deletion_range before result; the engine then hears what precedes the caret. */
    private fun put(deleteBefore: Int, committed: CharSequence?) {
        if (deleteBefore > 0) editor.deleteBefore(deleteBefore)
        if (!committed.isNullOrEmpty()) {
            editor.commit(committed)
            engine.textBeforeCaret(editor.textBeforeCaret(CONTEXT_CHARS))
        }
    }
}

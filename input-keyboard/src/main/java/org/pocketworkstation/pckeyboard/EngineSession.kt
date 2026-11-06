package org.pocketworkstation.pckeyboard

/**
 * The input method's side of the engine seam for an engine other than Hacker's Latin one (Droidtop/tracker#903 H6;
 * docs/SPEC.md 6a, "The engine seam"): keys go to [engine], and after each change its composing text goes to the
 * field and its candidates to the candidate bar's model ([bar]), through [editor]. LatinIME makes one when
 * [InputEngines] gives an engine for the language (H7 to H9: Mozc, librime, libhangul); with none, Hacker's own path
 * runs as it always has and no session exists.
 *
 * Pure: the [Editor] is the field and the bar, so a fake engine's whole round trip is tested on the JVM.
 */
class EngineSession(val engine: InputEngine, private val editor: Editor) {
    /** Where the session writes: the field's composing region and text, and the candidate bar. */
    interface Editor {
        /** Shows [composing] as the field's composing text (an empty one removes it). */
        fun setComposing(composing: InputEngine.Composing)

        /** Puts [text] in the field in place of the composing text. */
        fun commit(text: CharSequence)

        /** Leaves whatever the composing text shows in the field as typed. */
        fun finishComposing()

        /** The bar's model changed. */
        fun showCandidates(bar: CandidateBar)
    }

    val bar = CandidateBar()

    /** Whether something is composed now. */
    val composing: Boolean get() = !engine.composing().isEmpty

    /** A character key: true when the engine took it. */
    fun onChar(code: Int, nearby: IntArray?): Boolean {
        if (!engine.composes() || !engine.onChar(code, nearby)) return false
        refresh()
        return true
    }

    /** Backspace: true when it took a character from what is composed. */
    fun onDelete(): Boolean {
        if (!engine.composes() || !engine.onDelete()) return false
        refresh()
        return true
    }

    /** Space or enter: true when the engine took it (to convert, or to commit). */
    fun onKey(key: InputEngine.Key): Boolean {
        if (!engine.composes()) return false
        val result = engine.onKey(key)
        if (!result.handled) return false
        result.committed?.let { if (it.isNotEmpty()) editor.commit(it) }
        refresh()
        return true
    }

    /** Candidate [index] picked from the bar, by touch or pad. */
    fun pick(index: Int): Boolean {
        val text = engine.pick(index) ?: return false
        if (text.isNotEmpty()) editor.commit(text)
        refresh()
        return true
    }

    /** What is composed goes into the field as it stands (another key, a tool, closing the keyboard). */
    fun commit() {
        if (!composing) return
        val text = engine.commit()
        if (text.isNotEmpty()) editor.commit(text) else editor.finishComposing()
        refresh()
    }

    /** The field changed under the engine (the cursor moved away, a new field): what is shown stays, the engine forgets it. */
    fun leave() {
        val had = composing
        engine.reset()
        if (had) editor.finishComposing()
        bar.clear()
        editor.showCandidates(bar)
    }

    /** The engine's state, drawn again. */
    fun refresh() {
        editor.setComposing(engine.composing())
        bar.show(engine.candidates(), expandable = InputEngine.Capability.CONVERTS in engine.capabilities)
        editor.showCandidates(bar)
    }
}

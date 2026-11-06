package org.pocketworkstation.pckeyboard

/**
 * The engine seam (Droidtop/tracker#903 H6; docs/SPEC.md 6a, "The engine seam"): keys in, composing text and candidates
 * out. Hacker's own Latin path is the first engine ([LatinEngine]); a conversion engine (Mozc for Japanese, librime for
 * Chinese, libhangul for Korean, #903 H7 to H9) is another, registered in [InputEngines], and the input method drives
 * it through [EngineSession] without anything Latin changing.
 *
 * An engine is main-thread state, as Hacker's composing is. Every call that changes what is composed returns at once;
 * the host then reads [composing] and [candidates] and draws them (the composing text in the field, the candidates in
 * the one candidate bar, [CandidateView]). Pure: no Android types, so a fake engine is tested on the JVM.
 */
interface InputEngine {
    /** What an engine does, so the host offers only what fits (a correcting engine learns words; a converting one is driven by space). */
    enum class Capability {
        /** Offers words for what is being typed. */
        PREDICTS,

        /** Turns what is typed into other text (kana into kanji, pinyin into hanzi, jamo into syllables). */
        CONVERTS,

        /** Replaces what was typed with its best guess at a separator. */
        CORRECTS,
    }

    /** The keys an engine may take for itself: in a converting engine, space converts and enter commits. */
    enum class Key { SPACE, ENTER }

    /** What happened to an engine key: [handled] false leaves it to the keyboard; [committed] is text to put in the field. */
    class KeyResult(val handled: Boolean, val committed: CharSequence? = null) {
        companion object {
            @JvmField val NOT_HANDLED = KeyResult(false)
            @JvmField val HANDLED = KeyResult(true)
        }
    }

    /** A part of the composing text, [start] to [end] (exclusive) in its characters: a CJK conversion's clause. */
    class Segment(val start: Int, val end: Int) {
        override fun equals(other: Any?) = other is Segment && other.start == start && other.end == end
        override fun hashCode() = start * 31 + end
        override fun toString() = "[$start,$end)"
    }

    /**
     * The text being composed (a preedit). [segments] are its clauses, empty for one plain run (Latin); [focused] is
     * the clause being converted, drawn highlighted, -1 for none; [cursor] is where the caret sits in it, -1 for its
     * end (so a Composing over a buffer that grows, as Latin's does, stays right).
     */
    class Composing(
        val text: CharSequence,
        val segments: List<Segment> = emptyList(),
        val focused: Int = -1,
        val cursor: Int = -1,
    ) {
        val isEmpty: Boolean get() = text.isEmpty()

        /** Where the caret sits, in the text's characters. */
        val caret: Int get() = if (cursor in 0..text.length) cursor else text.length

        companion object {
            @JvmField val EMPTY = Composing("")
        }
    }

    /** One candidate, with an optional [annotation] drawn small beside it (a reading, a meaning, "katakana"). */
    class Candidate(val text: CharSequence, val annotation: CharSequence? = null) {
        override fun toString() = if (annotation == null) text.toString() else "$text ($annotation)"
    }

    /**
     * The candidates for what is composed. [focused] is the one the engine has chosen so far (space cycles it in a
     * conversion), -1 for none; [pageSize] is how many make a page in the engine's own paging, 0 when it does not page.
     */
    class Candidates(val items: List<Candidate>, val focused: Int = -1, val pageSize: Int = 0) {
        val isEmpty: Boolean get() = items.isEmpty()

        companion object {
            @JvmField val NONE = Candidates(emptyList())

            /** Plain words with no annotation, as the Latin path offers them. */
            @JvmStatic
            fun of(words: List<CharSequence>): Candidates = Candidates(words.map { Candidate(it) })
        }
    }

    /** What the engine does now; may change with the field and the settings. */
    val capabilities: Set<Capability>

    /** Whether the engine composes in the current field at all. False leaves every key to the keyboard as typed. */
    fun composes(): Boolean

    /** Whether the engine wants the candidate bar shown. */
    fun wantsCandidateBar(): Boolean

    /** A new field: [suggestionsAllowed] is false where the field asks for none (passwords, addresses, URIs). */
    fun startField(suggestionsAllowed: Boolean, password: Boolean) {}

    /** A character typed, with the [nearby] codes the touch also reached; false when the engine leaves it to the keyboard. */
    fun onChar(code: Int, nearby: IntArray?): Boolean

    /** Backspace: true when it took a character from what is composed; false when nothing is, and the field's own text goes. */
    fun onDelete(): Boolean

    /** An engine key ([Key]); [KeyResult.NOT_HANDLED] leaves it to the keyboard. */
    fun onKey(key: Key): KeyResult

    /** What is composed now; [Composing.EMPTY] when nothing is. */
    fun composing(): Composing

    /** The candidates for what is composed now. */
    fun candidates(): Candidates

    /** Candidate [index] chosen: the text to put in the field, or null when there is no such candidate. */
    fun pick(index: Int): CharSequence?

    /** Ends composing: the text to put in the field as it stands (empty when nothing was composed). */
    fun commit(): CharSequence

    /** Drops what is composed without putting anything in the field. */
    fun reset()
}

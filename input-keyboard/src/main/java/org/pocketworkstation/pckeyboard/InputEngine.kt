package org.pocketworkstation.pckeyboard

/**
 * The engine seam (Droidtop/tracker#903 H6, extended for conversion engines in H7a; docs/SPEC.md 6a, "The engine
 * seam"): keys in, composing text and candidates out. Hacker's own Latin path is the first engine ([LatinEngine]); a
 * conversion engine (Mozc for Japanese, librime for Chinese, libhangul for Korean, #903 H7 to H9) is another, registered
 * in [InputEngines], and the input method drives it through [EngineSession] without anything Latin changing.
 *
 * An engine is main-thread state, as Hacker's composing is. Every call that changes what is composed returns at once;
 * the host then reads [composing] and [candidates] again and draws them (the composing text in the field, the
 * candidates in the one candidate bar, [CandidateView]). Its own file work (loading data, saving what it learned) it
 * does on a thread of its own, never in these calls. Pure: no Android types, so a fake engine is tested on the JVM.
 *
 * **Every index and length here counts UTF-16 chars**, Android's unit, as [CharSequence] does. An engine that counts
 * code points (Mozc's Preedit.cursor and Segment.value_length do) converts with [CodePoints].
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

    /**
     * The keys an engine may take for itself. Space converts and enter commits in a converting engine; the rest reach
     * it only while it composes ([EngineSession.onEngineKey]).
     */
    enum class Key {
        SPACE,
        ENTER,

        /** The caret, or the focused clause while converting, one step left or right. */
        LEFT,
        RIGHT,

        /** The focused clause's candidates, one up or down. */
        UP,
        DOWN,

        /** The focused clause made one character shorter or longer. */
        SHIFT_LEFT,
        SHIFT_RIGHT,

        /** Cancels: a conversion back to its reading, or the reading dropped. */
        ESCAPE,

        /** Takes back the last commit into composing again. */
        UNDO,

        /** Ends a multi-tap key's cycling, so the same key next starts a new character (kana flick and toggle input). */
        TOGGLE,
    }

    /** Whether the engine can take keys: its library and data may still be loading, or missing. */
    sealed class State {
        object Ready : State()

        object Loading : State()

        /** It cannot work here; [reason] is one short plain line for the candidate strip. */
        class Unavailable(val reason: CharSequence) : State()
    }

    /**
     * What happened to a key: [handled] false leaves it to the keyboard. When it took the key, [deleteBefore] chars
     * before the composing text (or the caret, when nothing is composed) go first, then [committed] goes into the field
     * in place of the composing text, and then whatever [composing] now holds is drawn after it.
     */
    class KeyResult(val handled: Boolean, val committed: CharSequence? = null, val deleteBefore: Int = 0) {
        companion object {
            @JvmField val NOT_HANDLED = KeyResult(false)
            @JvmField val HANDLED = KeyResult(true)
        }
    }

    /**
     * A candidate picked: [committed] goes into the field (after [deleteBefore] chars before it go), and [composing] says
     * whether composing continues. A pick on the first of several clauses commits that clause and leaves the rest
     * composing; a pick from zero-query suggestions, with nothing composed, commits the candidate alone.
     */
    class Picked(val committed: CharSequence, val composing: Boolean = false, val deleteBefore: Int = 0)

    /** A part of the composing text, [start] to [end] (exclusive) in its chars: a CJK conversion's clause. */
    class Segment(val start: Int, val end: Int) {
        override fun equals(other: Any?) = other is Segment && other.start == start && other.end == end
        override fun hashCode() = start * 31 + end
        override fun toString() = "[$start,$end)"
    }

    /**
     * The text being composed (a preedit). [segments] are its clauses, empty for one plain run (Latin); [focused] is
     * the clause being converted, drawn highlighted, -1 for none; [cursor] is where the caret sits in it, -1 for its
     * end (so a Composing over a buffer that grows, as Latin's does, stays right). The host draws the caret there.
     */
    class Composing(
        val text: CharSequence,
        val segments: List<Segment> = emptyList(),
        val focused: Int = -1,
        val cursor: Int = -1,
    ) {
        val isEmpty: Boolean get() = text.isEmpty()

        /** Where the caret sits, in the text's chars. */
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
     * The candidates for what is composed, or with nothing composed the engine's suggestions for what may come next
     * (zero-query, after a commit). [focused] is the one the engine has chosen so far (space cycles it in a
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

    /** A composition mode the engine defines (hiragana, katakana, half-width); [label] is what the strip shows for it. */
    class Mode(val id: String, val label: CharSequence) {
        override fun equals(other: Any?) = other is Mode && other.id == id
        override fun hashCode() = id.hashCode()
        override fun toString() = id
    }

    /** What the engine does now; may change with the field and the settings. */
    val capabilities: Set<Capability>

    /** Whether it can take keys yet. While it is not [State.Ready], keys type as plain text and the strip says why. */
    val state: State get() = State.Ready

    /**
     * How far its data has come while [State.Loading], 0 to 100, or -1 when it cannot say (#903 H7c: Mozc's dictionary
     * downloading). The strip adds it to its "getting ready" line; a change is reported through [watchState].
     */
    val progress: Int get() = -1

    /**
     * [onChange] runs, on the main thread, when [state] changes (its data finished loading). The engine posts it there
     * from its own thread. Null stops it.
     */
    fun watchState(onChange: Runnable?) {}

    /** Whether the engine composes in the current field at all. False leaves every key to the keyboard as typed. */
    fun composes(): Boolean

    /** Whether the engine wants the candidate bar shown. */
    fun wantsCandidateBar(): Boolean

    /** A new field: [suggestionsAllowed] is false where the field asks for none (passwords, addresses, URIs). */
    fun startField(suggestionsAllowed: Boolean, password: Boolean) {}

    /**
     * The field's text just before the caret (at most [EngineSession.CONTEXT_CHARS] chars), when a field starts and
     * after each commit, for an engine that converts and predicts from what came before.
     */
    fun textBeforeCaret(text: CharSequence) {}

    /** The field ended: the engine saves what it learned, on its own thread. */
    fun finishField() {}

    /** Forgets every word and choice it has learned (for a setting); the files go on its own thread. */
    fun forgetLearned() {}

    /** A character typed, with the [nearby] codes the touch also reached; [KeyResult.NOT_HANDLED] leaves it to the keyboard. */
    fun onChar(code: Int, nearby: IntArray?): KeyResult

    /** Backspace: true when it took a character from what is composed; false when nothing is, and the field's own text goes. */
    fun onDelete(): Boolean

    /** An engine key ([Key]); [KeyResult.NOT_HANDLED] leaves it to the keyboard. */
    fun onKey(key: Key): KeyResult

    /** What is composed now; [Composing.EMPTY] when nothing is. */
    fun composing(): Composing

    /** The candidates now: for what is composed, or suggestions while nothing is. */
    fun candidates(): Candidates

    /** Candidate [index] chosen; null when there is no such candidate. */
    fun pick(index: Int): Picked?

    /** Ends composing: the text to put in the field as it stands (empty when nothing was composed). */
    fun commit(): CharSequence

    /** Drops what is composed, and any suggestions, without putting anything in the field. */
    fun reset()

    /** The composition modes it offers, in the order the strip cycles them; empty when it has none. */
    fun modes(): List<Mode> = emptyList()

    /** The mode it is in, null when it has none. */
    fun mode(): Mode? = null

    /** Switches to [mode], one of [modes]. */
    fun setMode(mode: Mode) {}
}

/**
 * Code points to UTF-16 chars (Droidtop/tracker#903 H7a), for an engine whose positions count code points, as Mozc's
 * do. "𠮷" and most emoji are one code point and two chars.
 */
object CodePoints {
    /** The char index in [text] of code point [cpIndex], clamped to the text. */
    @JvmStatic
    fun toUtf16(text: CharSequence, cpIndex: Int): Int {
        if (cpIndex <= 0) return 0
        var at = 0
        var count = 0
        while (at < text.length && count < cpIndex) {
            at += if (Character.isHighSurrogate(text[at]) && at + 1 < text.length && Character.isLowSurrogate(text[at + 1])) 2 else 1
            count++
        }
        return at
    }

    /** How many chars the last [cpCount] code points of [text] take: a deletion before the caret counted in code points. */
    @JvmStatic
    fun utf16Before(text: CharSequence, cpCount: Int): Int {
        var at = text.length
        var count = 0
        while (at > 0 && count < cpCount) {
            at -= if (Character.isLowSurrogate(text[at - 1]) && at - 2 >= 0 && Character.isHighSurrogate(text[at - 2])) 2 else 1
            count++
        }
        return text.length - at
    }

    /** Clauses laid end to end over [text] from their lengths in code points, as Mozc's Segment.value_length gives them. */
    @JvmStatic
    fun segments(text: CharSequence, cpLengths: IntArray): List<InputEngine.Segment> {
        val out = ArrayList<InputEngine.Segment>(cpLengths.size)
        var cp = 0
        var start = 0
        for (length in cpLengths) {
            cp += length
            val end = toUtf16(text, cp)
            out += InputEngine.Segment(start, end)
            start = end
        }
        return out
    }
}

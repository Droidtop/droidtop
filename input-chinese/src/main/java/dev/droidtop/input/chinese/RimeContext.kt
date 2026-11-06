package dev.droidtop.input.chinese

import org.pocketworkstation.pckeyboard.InputEngine

/**
 * What a RIME session shows after a key (Droidtop/tracker#903 H8b): librime's RimeContext, read by droidtop's JNI
 * (rime_jni.cc), which has already turned librime's UTF-8 byte offsets into UTF-16 chars. So every index here counts
 * chars, as the seam does. Pure.
 */
class RimeContext(
    /** The preedit, as librime formats it ("ni hao", or "你hao" once a part is picked). */
    val preedit: String,
    /** The caret in [preedit]. */
    val cursor: Int,
    /** The part being converted, [selStart] to [selEnd]; equal when none. */
    val selStart: Int,
    val selEnd: Int,
    val candidates: List<InputEngine.Candidate>,
    /** The highlighted candidate's index in [candidates] (across librime's pages), -1 for none. */
    val highlighted: Int,
    /** librime's menu page size (5 in luna_pinyin). */
    val pageSize: Int,
    /** Whether librime is passing letters through (its ascii_mode). */
    val asciiMode: Boolean,
) {
    /**
     * The seam's composing text: the preedit, split into the part before the selection, the selection (focused) and the
     * part after, the empty ones left out; the caret where librime has it.
     */
    fun composing(): InputEngine.Composing {
        if (preedit.isEmpty()) return InputEngine.Composing.EMPTY
        val length = preedit.length
        val start = selStart.coerceIn(0, length)
        val end = selEnd.coerceIn(start, length)
        if (start == end) return InputEngine.Composing(preedit, cursor = cursor.coerceIn(0, length))
        val segments = ArrayList<InputEngine.Segment>(3)
        if (start > 0) segments += InputEngine.Segment(0, start)
        val focused = segments.size
        segments += InputEngine.Segment(start, end)
        if (end < length) segments += InputEngine.Segment(end, length)
        return InputEngine.Composing(preedit, segments, focused, cursor.coerceIn(0, length))
    }

    /** The seam's candidates; the bar pages them itself (pageSize 0), as it does Mozc's. */
    fun candidates(): InputEngine.Candidates {
        if (candidates.isEmpty()) return InputEngine.Candidates.NONE
        return InputEngine.Candidates(candidates, if (highlighted in candidates.indices) highlighted else -1)
    }

    companion object {
        @JvmField val EMPTY = RimeContext("", 0, 0, 0, emptyList(), -1, 0, false)

        /** The ints rime_jni.cc's getContext gives, in this order. */
        const val CURSOR = 0
        const val SEL_START = 1
        const val SEL_END = 2
        const val HIGHLIGHTED = 3
        const val PAGE_SIZE = 4
        const val ASCII_MODE = 5
        const val INTS = 6

        /**
         * Reads rime_jni.cc's getContext answer: { String preedit, int[INTS], String[] texts, String[] comments }.
         * Null, or anything else, reads as nothing composed.
         */
        @JvmStatic
        fun fromJni(answer: Array<Any?>?): RimeContext {
            if (answer == null || answer.size < 4) return EMPTY
            val preedit = answer[0] as? String ?: ""
            val ints = answer[1] as? IntArray ?: return EMPTY
            if (ints.size < INTS) return EMPTY
            val texts = (answer[2] as? Array<*>).orEmpty()
            val comments = (answer[3] as? Array<*>).orEmpty()
            val candidates = texts.mapIndexed { i, text ->
                val comment = comments.getOrNull(i) as? String
                InputEngine.Candidate(text as? String ?: "", comment?.takeIf { it.isNotEmpty() })
            }
            return RimeContext(preedit, ints[CURSOR], ints[SEL_START], ints[SEL_END], candidates, ints[HIGHLIGHTED], ints[PAGE_SIZE], ints[ASCII_MODE] != 0)
        }
    }
}

package dev.droidtop.input.chinese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketworkstation.pckeyboard.InputEngine

/** Droidtop/tracker#903 H8b: librime's context, as rime_jni.cc hands it over (offsets already UTF-16), for the seam. */
class RimeContextTest {
    private fun ints(cursor: Int, selStart: Int, selEnd: Int, highlighted: Int = -1, pageSize: Int = 5, ascii: Int = 0) =
        intArrayOf(cursor, selStart, selEnd, highlighted, pageSize, ascii)

    @Test fun `the JNI's answer is read in its order, empty comments as none`() {
        val context = RimeContext.fromJni(arrayOf("ni hao", ints(6, 0, 6, highlighted = 7, ascii = 1), arrayOf("你好", "你"), arrayOf("", "nǐ")))
        assertEquals("ni hao", context.preedit)
        assertEquals(6, context.cursor)
        assertEquals(7, context.highlighted)
        assertEquals(5, context.pageSize)
        assertTrue(context.asciiMode)
        assertEquals(listOf("你好", "你 (nǐ)"), context.candidates.map { it.toString() })
        assertNull(context.candidates[0].annotation)
    }

    @Test fun `nothing, or something malformed, reads as nothing composed`() {
        assertSame(RimeContext.EMPTY, RimeContext.fromJni(null))
        assertSame(RimeContext.EMPTY, RimeContext.fromJni(arrayOf("x")))
        assertSame(RimeContext.EMPTY, RimeContext.fromJni(arrayOf("x", intArrayOf(1), arrayOf<String>(), arrayOf<String>())))
        assertTrue(RimeContext.fromJni(arrayOf(null, ints(0, 0, 0), null, null)).composing().isEmpty)
        assertSame(InputEngine.Composing.EMPTY, RimeContext.EMPTY.composing())
        assertSame(InputEngine.Candidates.NONE, RimeContext.EMPTY.candidates())
    }

    @Test fun `the selection is the focused part, between what is picked and what follows, in UTF-16 chars`() {
        // "𠮷" is one code point and two chars; rime_jni.cc has turned librime's byte offsets into these.
        val text = "𠮷ni hao"
        val context = RimeContext.fromJni(arrayOf(text, ints(4, 2, 4), arrayOf("你"), arrayOf<String?>(null)))
        val composing = context.composing()
        assertEquals(listOf(InputEngine.Segment(0, 2), InputEngine.Segment(2, 4), InputEngine.Segment(4, 8)), composing.segments)
        assertEquals(1, composing.focused)
        assertEquals(4, composing.caret)
        // The whole preedit selected: one focused part.
        val whole = RimeContext.fromJni(arrayOf("nihao", ints(5, 0, 5), arrayOf("你好"), arrayOf<String?>(null))).composing()
        assertEquals(listOf(InputEngine.Segment(0, 5)), whole.segments)
        assertEquals(0, whole.focused)
        // No selection: one plain run, and offsets past the end are clamped.
        val plain = RimeContext.fromJni(arrayOf("ni", ints(9, 3, 3), arrayOf<String>(), arrayOf<String>())).composing()
        assertTrue(plain.segments.isEmpty())
        assertEquals(2, plain.caret)
    }

    @Test fun `the highlight is kept when it is among the candidates read, and the bar pages them itself`() {
        val texts = arrayOf("一", "二", "三")
        val comments = arrayOfNulls<String>(3)
        assertEquals(2, RimeContext.fromJni(arrayOf("san", ints(3, 0, 3, highlighted = 2), texts, comments)).candidates().focused)
        assertEquals(-1, RimeContext.fromJni(arrayOf("san", ints(3, 0, 3, highlighted = 120), texts, comments)).candidates().focused)
        assertEquals(0, RimeContext.fromJni(arrayOf("san", ints(3, 0, 3, highlighted = 0), texts, comments)).candidates().pageSize)
    }
}

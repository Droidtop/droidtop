package dev.droidtop.input.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.Annotation
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateList
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWindow
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateWord
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.DeletionRange
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Preedit
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Result
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Status
import org.pocketworkstation.pckeyboard.InputEngine

/** Droidtop/tracker#903 H7c: Mozc's Output read as the seam's composing text and candidates, in UTF-16. */
class MozcOutputTest {
    private fun segment(value: String, highlight: Boolean = false) = Preedit.Segment.newBuilder()
        .setValue(value)
        .setValueLength(value.codePointCount(0, value.length))
        .setAnnotation(if (highlight) Preedit.Segment.Annotation.HIGHLIGHT else Preedit.Segment.Annotation.UNDERLINE)
        .build()

    private fun preedit(cursor: Int, vararg segments: Preedit.Segment, highlighted: Int? = null): Output {
        val p = Preedit.newBuilder().setCursor(cursor)
        segments.forEach { p.addSegment(it) }
        if (highlighted != null) p.setHighlightedPosition(highlighted)
        return Output.newBuilder().setPreedit(p).build()
    }

    @Test fun `a reading is one plain run with the caret where Mozc has it`() {
        val c = MozcOutput.composing(preedit(2, segment("にほんご")))
        assertEquals("にほんご", c.text.toString())
        assertTrue("one clause composing is drawn as a plain run", c.segments.isEmpty())
        assertEquals(-1, c.focused)
        assertEquals(2, c.cursor)
    }

    @Test fun `clauses become segments and highlighted_position names the focused one`() {
        val c = MozcOutput.composing(preedit(5, segment("日本"), segment("語"), segment("です"), highlighted = 2))
        assertEquals("日本語です", c.text.toString())
        assertEquals(listOf(InputEngine.Segment(0, 2), InputEngine.Segment(2, 3), InputEngine.Segment(3, 5)), c.segments)
        assertEquals(1, c.focused)
        assertEquals(5, c.cursor)
    }

    @Test fun `without highlighted_position the HIGHLIGHT clause is focused`() {
        val c = MozcOutput.composing(preedit(3, segment("日本"), segment("語", highlight = true)))
        assertEquals(1, c.focused)
    }

    @Test fun `astral characters count one code point in Mozc and two chars in the seam`() {
        // 𠮷 and 😀 are each one code point (Segment.value_length 1) and two UTF-16 chars.
        val c = MozcOutput.composing(preedit(2, segment("𠮷野"), segment("😀"), highlighted = 2))
        assertEquals("𠮷野😀", c.text.toString())
        assertEquals(listOf(InputEngine.Segment(0, 3), InputEngine.Segment(3, 5)), c.segments)
        assertEquals("highlighted_position 2 is the second clause's start in code points", 1, c.focused)
        assertEquals("cursor 2 in code points is after 野: char 3", 3, c.cursor)
        val caretInside = MozcOutput.composing(preedit(1, segment("𠮷野")))
        assertEquals(2, caretInside.cursor)
    }

    @Test fun `nothing composed is empty`() {
        assertTrue(MozcOutput.composing(Output.getDefaultInstance()).isEmpty)
        assertTrue(MozcOutput.composing(preedit(0)).isEmpty)
    }

    private fun word(id: Int, value: String, annotation: Annotation? = null): CandidateWord {
        val w = CandidateWord.newBuilder().setId(id).setValue(value)
        if (annotation != null) w.setAnnotation(annotation)
        return w.build()
    }

    @Test fun `all_candidate_words are the candidates, with prefix suffix and description as the annotation`() {
        val out = Output.newBuilder().setAllCandidateWords(
            CandidateList.newBuilder().setFocusedIndex(1)
                .addCandidates(word(7, "日本語"))
                .addCandidates(word(3, "ニホンゴ", Annotation.newBuilder().setDescription("カタカナ").build()))
                .addCandidates(word(-2, "ﾆﾎﾝｺﾞ", Annotation.newBuilder().setPrefix("[半]").setDescription("カタカナ").build()))
                .addCandidates(word(11, "𠮷", Annotation.newBuilder().setSuffix("環境依存").build())),
        ).build()
        val c = MozcOutput.candidates(out)
        assertEquals(listOf("日本語", "ニホンゴ", "ﾆﾎﾝｺﾞ", "𠮷"), c.items.map { it.text.toString() })
        assertEquals(listOf(null, "カタカナ", "[半] カタカナ", "環境依存"), c.items.map { it.annotation?.toString() })
        assertEquals(1, c.focused)
        assertEquals("a pick sends Mozc's id, not the index", 3, MozcOutput.candidateId(out, 1))
        assertEquals(-2, MozcOutput.candidateId(out, 2))
        assertNull(MozcOutput.candidateId(out, 4))
    }

    @Test fun `zero-query suggestions arrive with nothing composed`() {
        val out = Output.newBuilder()
            .setResult(Result.newBuilder().setType(Result.ResultType.STRING).setValue("日本語"))
            .setAllCandidateWords(CandidateList.newBuilder().addCandidates(word(0, "を")).addCandidates(word(1, "の")))
            .build()
        assertEquals("日本語", MozcOutput.committed(out))
        assertTrue(MozcOutput.composing(out).isEmpty)
        assertEquals(listOf("を", "の"), MozcOutput.candidates(out).items.map { it.text.toString() })
        assertEquals(-1, MozcOutput.candidates(out).focused)
    }

    @Test fun `the candidate window is the fallback`() {
        val out = Output.newBuilder().setCandidateWindow(
            CandidateWindow.newBuilder().setSize(2).setPosition(0).setFocusedIndex(1)
                .addCandidate(CandidateWindow.Candidate.newBuilder().setIndex(0).setValue("感じ").setId(4))
                .addCandidate(CandidateWindow.Candidate.newBuilder().setIndex(1).setValue("漢字").setId(9)),
        ).build()
        assertEquals(listOf("感じ", "漢字"), MozcOutput.candidates(out).items.map { it.text.toString() })
        assertEquals(1, MozcOutput.candidates(out).focused)
        assertEquals(9, MozcOutput.candidateId(out, 1))
    }

    @Test fun `deletion_range becomes chars before the caret`() {
        fun range(offset: Int, length: Int) = Output.newBuilder().setDeletionRange(DeletionRange.newBuilder().setOffset(offset).setLength(length)).build()
        assertEquals(2, MozcOutput.deleteBefore(range(-2, 2), "これは日本"))
        assertEquals("𠮷 is two chars for one code point", 3, MozcOutput.deleteBefore(range(-2, 2), "は𠮷野"))
        assertEquals("a range that does not end at the caret is not taken", 0, MozcOutput.deleteBefore(range(-3, 2), "日本語"))
        assertEquals(0, MozcOutput.deleteBefore(Output.getDefaultInstance(), "日本語"))
    }

    @Test fun `the mode comes from Output mode or its status`() {
        assertEquals(CompositionMode.FULL_KATAKANA, MozcOutput.mode(Output.newBuilder().setMode(CompositionMode.FULL_KATAKANA).build()))
        assertEquals(CompositionMode.HALF_ASCII, MozcOutput.mode(Output.newBuilder().setStatus(Status.newBuilder().setMode(CompositionMode.HALF_ASCII)).build()))
        assertNull(MozcOutput.mode(Output.getDefaultInstance()))
        assertNull(MozcOutput.committed(Output.getDefaultInstance()))
    }
}

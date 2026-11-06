package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine seam's round trip with a conversion engine (Droidtop/tracker#903 H6 and H7a): [FakeKanaEngine] driven
 * through [EngineSession], as the input method drives a language's own engine, into a field and the candidate bar's
 * model. Composing clauses with the focused one, paged and annotated candidates, the pad's selection and the pick all
 * arrive; so do what Mozc needs (H7a): the caret inside the composing text, text beyond the BMP, a partial commit,
 * suggestions with nothing composed, an engine not ready yet, the clause keys and modes, a deletion before the caret,
 * the text before the caret, and the field's end.
 */
class EngineSessionTest {
    /** A text field and the bar, as the input method shows them. */
    private class Field : EngineSession.Editor {
        val text = StringBuilder()
        var shown: InputEngine.Composing = InputEngine.Composing.EMPTY
        var finished = 0
        var bar: CandidateBar? = null

        override fun setComposing(composing: InputEngine.Composing) {
            shown = composing
        }

        override fun commit(text: CharSequence) {
            this.text.append(text)
            shown = InputEngine.Composing.EMPTY
        }

        override fun deleteBefore(count: Int) {
            text.setLength(maxOf(0, text.length - count))
        }

        override fun textBeforeCaret(max: Int): CharSequence = text.takeLast(max)

        override fun finishComposing() {
            text.append(shown.text)
            shown = InputEngine.Composing.EMPTY
            finished++
        }

        override fun showCandidates(bar: CandidateBar) {
            this.bar = bar
        }

        /** What a person sees in the field: the text, then what is being composed. */
        override fun toString() = text.toString() + shown.text
    }

    private fun session(engine: FakeKanaEngine = FakeKanaEngine()): Pair<EngineSession, Field> {
        val field = Field()
        return EngineSession(engine, field, "Japanese is getting ready…") to field
    }

    private val EngineSession.fake get() = engine as FakeKanaEngine

    /** What a person sees, with the caret drawn as a bar. */
    private fun Field.withCaret() = text.toString() + shown.text.substring(0, shown.caret) + "|" + shown.text.substring(shown.caret)

    private fun EngineSession.type(romaji: String) = romaji.forEach { assertTrue("$it taken", onChar(it.code, null)) }

    @Test fun `kana compose as one clause, with candidates and an annotation in the bar`() {
        val (s, field) = session()
        s.type("kanjidesu")
        assertEquals("かんじです", field.shown.text.toString())
        assertEquals(listOf(InputEngine.Segment(0, 5)), field.shown.segments)
        assertEquals(-1, field.shown.focused)
        val bar = field.bar!!
        assertEquals(listOf("かんじです", "カンジデス"), bar.items.map { it.text.toString() })
        assertEquals("katakana", bar.items[1].annotation)
        assertTrue("a converting engine offers the grid", bar.expandable)
        assertTrue(s.composing)
    }

    @Test fun `space converts into clauses, the first focused, and cycles its candidates`() {
        val (s, field) = session()
        s.type("kanjidesu")
        assertTrue(s.onKey(InputEngine.Key.SPACE))
        assertEquals("漢字です", field.shown.text.toString())
        assertEquals(listOf(InputEngine.Segment(0, 2), InputEngine.Segment(2, 4)), field.shown.segments)
        assertEquals(0, field.shown.focused)
        val bar = field.bar!!
        assertEquals(12, bar.items.size)
        assertEquals(0, bar.focused)
        assertEquals("kanji", bar.items[0].annotation)
        assertNull(bar.items[5].annotation)
        assertEquals("the engine's own pages of nine", 9, bar.pageSize)
        assertEquals(2, bar.pageCount)

        assertTrue(s.onKey(InputEngine.Key.SPACE))
        assertEquals("感じです", field.shown.text.toString())
        assertEquals(1, field.bar!!.focused)
        assertEquals("", field.text.toString())
    }

    @Test fun `the grid's pages and the pad's selection carry the pick back to the engine`() {
        val (s, field) = session()
        s.type("kanjidesu")
        s.onKey(InputEngine.Key.SPACE)
        s.onKey(InputEngine.Key.SPACE)
        val bar = field.bar!!
        assertTrue(bar.expand())
        bar.layoutGrid(columns = 3, rows = 3)
        assertTrue(bar.padEnter())
        assertEquals("the ring starts on the engine's choice", 1, bar.selectedCandidate())
        bar.move(CandidateBar.Move.DOWN)
        bar.move(CandidateBar.Move.DOWN)
        assertEquals(7, bar.selectedCandidate())
        bar.move(CandidateBar.Move.DOWN)
        assertEquals("onto the second page", 1, bar.page)
        assertEquals(10, bar.selectedCandidate())
        assertEquals("間時", bar.items[bar.selectedCandidate()].text)

        // A pick on the first clause commits it and leaves the rest composing (H7a).
        assertTrue(s.pick(bar.selectedCandidate()))
        assertEquals("間時", field.text.toString())
        assertEquals("です", field.shown.text.toString())
        assertTrue(s.composing)
        assertEquals(listOf("です", "デス"), field.bar!!.items.map { it.text.toString() })

        assertTrue(s.pick(0))
        assertEquals("間時です", field.text.toString())
        assertTrue(field.shown.isEmpty)
        assertFalse(s.composing)
        assertTrue(field.bar!!.items.isEmpty())
        assertFalse("the grid closes with nothing to show", field.bar!!.expanded)
        assertFalse("and the pad leaves it", field.bar!!.padActive)
    }

    @Test fun `enter commits the reading as typed, and is the keyboard's when nothing is composed`() {
        val (s, field) = session()
        assertFalse(s.onKey(InputEngine.Key.ENTER))
        assertFalse(s.onKey(InputEngine.Key.SPACE))
        s.type("ai")
        assertTrue(s.onKey(InputEngine.Key.ENTER))
        assertEquals("あい", field.toString())
        assertFalse(s.composing)
    }

    @Test fun `a candidate picked before converting is put in as it reads`() {
        val (s, field) = session()
        s.type("kaki")
        assertTrue(s.pick(1))
        assertEquals("カキ", field.toString())
        assertFalse(s.pick(5))
    }

    @Test fun `backspace undoes the conversion, then the kana, then is the field's`() {
        val (s, field) = session()
        s.type("ka")
        s.onKey(InputEngine.Key.SPACE)
        assertTrue(s.onDelete())
        assertEquals("か", field.shown.text.toString())
        assertTrue(s.onDelete())
        assertTrue(field.shown.isEmpty)
        assertFalse(s.onDelete())
    }

    @Test fun `another key commits what is composed first, as the input method does when the engine declines it`() {
        val (s, field) = session()
        s.type("su")
        assertFalse(s.onChar('1'.code, null))
        s.commit()
        assertEquals("す", field.text.toString())
        s.commit()
        assertEquals("nothing more", "す", field.text.toString())
    }

    @Test fun `leaving keeps what was shown as typed and forgets it`() {
        val (s, field) = session()
        s.type("kon")
        s.leave()
        assertEquals("こn", field.text.toString())
        assertEquals(1, field.finished)
        assertFalse(s.composing)
        assertTrue(field.bar!!.items.isEmpty())
        s.leave()
        assertEquals("nothing composed, nothing finished", 1, field.finished)
    }

    @Test fun `a field that wants no suggestions gets no composing`() {
        val (s, field) = session()
        s.engine.startField(suggestionsAllowed = false, password = true)
        assertFalse(s.onChar('k'.code, null))
        assertFalse(s.onKey(InputEngine.Key.SPACE))
        assertEquals("", field.toString())
    }

    // H7a: what a conversion engine such as Mozc needs from the seam.

    @Test fun `the caret is drawn where the engine has it, in UTF-16 chars over text beyond the BMP`() {
        val (s, field) = session()
        s.type("qa")
        assertEquals("𠮷あ", field.shown.text.toString())
        assertEquals("𠮷 is two chars", 3, field.shown.text.length)
        assertEquals("𠮷あ|", field.withCaret())
        assertTrue(s.onKey(InputEngine.Key.LEFT))
        assertEquals("𠮷|あ", field.withCaret())
        assertTrue(s.onKey(InputEngine.Key.LEFT))
        assertEquals("|𠮷あ", field.withCaret())
        s.type("ka")
        assertEquals("か|𠮷あ", field.withCaret())
        assertTrue(s.onKey(InputEngine.Key.RIGHT))
        assertTrue(s.onDelete())
        assertEquals("the whole of 𠮷 goes", "か|あ", field.withCaret())
        s.type("x")
        assertEquals("か😀|あ", field.withCaret())
        assertEquals(listOf(InputEngine.Segment(0, 4)), field.shown.segments)
    }

    @Test fun `code points convert to UTF-16 chars`() {
        val text = "a𠮷b😀"
        assertEquals(0, CodePoints.toUtf16(text, 0))
        assertEquals(1, CodePoints.toUtf16(text, 1))
        assertEquals(3, CodePoints.toUtf16(text, 2))
        assertEquals(4, CodePoints.toUtf16(text, 3))
        assertEquals(6, CodePoints.toUtf16(text, 4))
        assertEquals("clamped to the text", 6, CodePoints.toUtf16(text, 9))
        assertEquals(0, CodePoints.toUtf16(text, -1))
        assertEquals(2, CodePoints.utf16Before(text, 1))
        assertEquals(3, CodePoints.utf16Before(text, 2))
        assertEquals(5, CodePoints.utf16Before(text, 3))
        assertEquals(6, CodePoints.utf16Before(text, 99))
        assertEquals(listOf(InputEngine.Segment(0, 3), InputEngine.Segment(3, 6)), CodePoints.segments(text, intArrayOf(2, 2)))
        assertEquals("a lone surrogate counts as one", 1, CodePoints.toUtf16("\uD842x", 1))
    }

    @Test fun `typing after a conversion commits it and starts a new reading in one key`() {
        val (s, field) = session()
        s.type("kanji")
        s.onKey(InputEngine.Key.SPACE)
        s.type("a")
        assertEquals("漢字", field.text.toString())
        assertEquals("あ", field.shown.text.toString())
    }

    @Test fun `with nothing composed the engine's suggestions show, and a pick commits one`() {
        val (s, field) = session()
        s.type("kanji")
        s.onKey(InputEngine.Key.SPACE)
        assertTrue(s.onKey(InputEngine.Key.ENTER))
        assertEquals("漢字", field.text.toString())
        assertFalse(s.composing)
        assertEquals("it was given the text before the caret after the commit", "漢字", s.fake.context)
        val bar = field.bar!!
        assertEquals(listOf("です", "を"), bar.items.map { it.text.toString() })
        assertTrue(s.pick(1))
        assertEquals("漢字を", field.text.toString())
        assertTrue(field.shown.isEmpty)
        assertTrue("nothing follows を", field.bar!!.items.isEmpty())
    }

    @Test fun `suggestions go when another key types`() {
        val (s, field) = session()
        s.type("kanji")
        s.onKey(InputEngine.Key.SPACE)
        s.onKey(InputEngine.Key.ENTER)
        assertFalse(field.bar!!.items.isEmpty())
        assertFalse(s.onChar('1'.code, null))
        s.commit()
        assertTrue(field.bar!!.items.isEmpty())
        assertEquals("漢字", field.text.toString())
    }

    @Test fun `an engine not ready lets keys type as plain text and the strip says so, until it is`() {
        val engine = FakeKanaEngine(InputEngine.State.Loading)
        val (s, field) = session(engine)
        s.startField(suggestionsAllowed = true, password = false)
        assertFalse(s.ready)
        assertFalse("k types as it is", s.onChar('k'.code, null))
        assertFalse(s.onKey(InputEngine.Key.SPACE))
        assertFalse(s.onDelete())
        assertFalse(s.pick(0))
        assertFalse(s.cycleMode())
        s.commit()
        assertTrue(field.shown.isEmpty)
        assertEquals("Japanese is getting ready…", field.bar!!.notice.toString())
        assertTrue(field.bar!!.items.isEmpty())
        assertNull("no mode button meanwhile", field.bar!!.modeLabel)

        engine.becomes(InputEngine.State.Ready)
        assertNull("the strip is drawn again when it is ready", field.bar!!.notice)
        assertEquals("あ", field.bar!!.modeLabel)
        s.type("ka")
        assertEquals("か", field.shown.text.toString())

        engine.becomes(InputEngine.State.Unavailable("Japanese needs a download"))
        assertEquals("Japanese needs a download", field.bar!!.notice.toString())
        assertTrue(field.shown.isEmpty)
        assertEquals("what it composed stays as typed", "か", field.toString())
        s.leave()
        assertEquals("the reason stays after leaving", "Japanese needs a download", field.bar!!.notice.toString())
        s.close()
        engine.becomes(InputEngine.State.Ready)
        assertEquals("a closed session hears nothing", "Japanese needs a download", field.bar!!.notice.toString())
    }

    @Test fun `the clause keys move the focus, resize the clause and cancel`() {
        val (s, field) = session()
        s.type("kanjidesu")
        s.onKey(InputEngine.Key.SPACE)
        assertEquals(0, field.shown.focused)
        assertTrue(s.onKey(InputEngine.Key.RIGHT))
        assertEquals(1, field.shown.focused)
        assertEquals("the bar follows the focused clause", listOf("です", "デス"), field.bar!!.items.map { it.text.toString() })
        assertTrue(s.onKey(InputEngine.Key.DOWN))
        assertEquals("漢字デス", field.shown.text.toString())
        assertTrue(s.onKey(InputEngine.Key.UP))
        assertEquals("漢字です", field.shown.text.toString())
        assertTrue(s.onKey(InputEngine.Key.LEFT))
        assertEquals(0, field.shown.focused)

        assertTrue(s.onKey(InputEngine.Key.SHIFT_LEFT))
        assertEquals("the first clause one shorter, its じ joining です", "かんじです", field.shown.text.toString())
        assertEquals(listOf(InputEngine.Segment(0, 2), InputEngine.Segment(2, 5)), field.shown.segments)
        assertTrue(s.onKey(InputEngine.Key.SHIFT_RIGHT))
        assertEquals("漢字です", field.shown.text.toString())
        assertEquals(listOf(InputEngine.Segment(0, 2), InputEngine.Segment(2, 4)), field.shown.segments)

        assertTrue(s.onKey(InputEngine.Key.ESCAPE))
        assertEquals("back to the reading", "かんじです", field.shown.text.toString())
        assertEquals(-1, field.shown.focused)
        assertTrue(s.onKey(InputEngine.Key.ESCAPE))
        assertTrue("then the reading goes", field.shown.isEmpty)
        assertEquals("", field.text.toString())
    }

    @Test fun `the arrows and Escape are the engine's only while it composes`() {
        val (s, _) = session()
        for (key in listOf(InputEngine.Key.LEFT, InputEngine.Key.RIGHT, InputEngine.Key.UP, InputEngine.Key.DOWN,
            InputEngine.Key.SHIFT_LEFT, InputEngine.Key.SHIFT_RIGHT, InputEngine.Key.ESCAPE)) {
            assertFalse("$key moves the field's own cursor", s.onKey(key))
        }
        val recording = object : InputEngine by FakeKanaEngine() {
            val seen = ArrayList<InputEngine.Key>()
            override fun onKey(key: InputEngine.Key): InputEngine.KeyResult {
                seen += key
                return InputEngine.KeyResult.NOT_HANDLED
            }
        }
        val other = EngineSession(recording, Field())
        InputEngine.Key.values().forEach { other.onKey(it) }
        assertEquals("with nothing composed only these reach it",
            listOf(InputEngine.Key.SPACE, InputEngine.Key.ENTER, InputEngine.Key.UNDO, InputEngine.Key.TOGGLE), recording.seen)
    }

    @Test fun `a multi-tap key cycles until toggle stops it`() {
        val (s, field) = session()
        assertTrue(s.onChar('7'.code, null))
        assertTrue(s.onChar('7'.code, null))
        assertEquals("み", field.shown.text.toString())
        assertTrue(s.onKey(InputEngine.Key.TOGGLE))
        assertTrue(s.onChar('7'.code, null))
        assertEquals("みま", field.shown.text.toString())
    }

    @Test fun `the mode shows on the strip and cycles`() {
        val (s, field) = session()
        s.refresh()
        assertEquals("あ", field.bar!!.modeLabel)
        assertTrue(s.cycleMode())
        assertEquals("ア", field.bar!!.modeLabel)
        s.type("ka")
        assertEquals("カ", field.shown.text.toString())
        assertTrue(s.cycleMode())
        assertEquals("A", field.bar!!.modeLabel)
        s.type("b")
        assertEquals("カb", field.shown.text.toString())
        assertTrue(s.cycleMode())
        assertEquals("round to the first", "あ", field.bar!!.modeLabel)
    }

    @Test fun `undo deletes the commit before the caret, in UTF-16 chars, and composes it again`() {
        val (s, field) = session()
        field.text.append("x")
        s.type("qa")
        s.onKey(InputEngine.Key.ENTER)
        assertEquals("x𠮷あ", field.text.toString())
        assertTrue(s.onKey(InputEngine.Key.UNDO))
        assertEquals("three chars deleted for two code points", "x", field.text.toString())
        assertEquals("𠮷あ", field.shown.text.toString())
        assertTrue(s.composing)
    }

    @Test fun `a field's start gives the text before the caret, and its end lets the engine save`() {
        val (s, field) = session()
        field.text.append("この漢字")
        s.startField(suggestionsAllowed = true, password = false)
        assertEquals("この漢字", s.fake.context)
        assertEquals("suggestions from the field's own text", listOf("です", "を"), field.bar!!.items.map { it.text.toString() })
        s.type("ka")
        s.finishField()
        assertEquals("この漢字か", field.text.toString())
        assertEquals(1, field.finished)
        assertEquals(1, s.fake.finishedFields)
        assertTrue(field.bar!!.items.isEmpty())

        field.text.setLength(0)
        field.text.append("x".repeat(30))
        s.startField(suggestionsAllowed = true, password = false)
        assertEquals("capped", EngineSession.CONTEXT_CHARS, s.fake.context.length)
    }

    @Test fun `it learns a pick and forgets it when asked`() {
        val (s, field) = session()
        s.type("kanji")
        s.onKey(InputEngine.Key.SPACE)
        s.pick(1)
        assertEquals("感じ", field.text.toString())
        s.type("kanji")
        s.onKey(InputEngine.Key.SPACE)
        assertEquals("感じ", field.shown.text.toString())
        s.forgetLearned()
        s.type("kanji")
        s.onKey(InputEngine.Key.SPACE)
        assertEquals("漢字", field.shown.text.toString())
    }
}

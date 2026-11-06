package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine seam's round trip with a conversion engine (Droidtop/tracker#903 H6): [FakeKanaEngine] driven through
 * [EngineSession], as the input method drives a language's own engine, into a field and the candidate bar's model.
 * Composing clauses with the focused one, paged and annotated candidates, the pad's selection and the pick all arrive.
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

    private fun session(): Pair<EngineSession, Field> {
        val field = Field()
        return EngineSession(FakeKanaEngine(), field) to field
    }

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

        assertTrue(s.pick(bar.selectedCandidate()))
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
}

package org.pocketworkstation.pckeyboard.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketworkstation.pckeyboard.ClipboardHistory

/**
 * A clip from a paired device lands on the keyboard's clipboard panel under the history's own rules (docs/SPEC.md,
 * "Type from another device", "On the keyboard", Droidtop/tracker#903).
 */
class PairedClipsTest {
    private val history = ClipboardHistory()

    private fun keep(
        text: String,
        historyOn: Boolean = true,
        incognito: Boolean = false,
        fieldSensitive: Boolean = false,
        markedSensitive: Boolean = false,
    ) = PairedClips.keep(history, text, 1_000L, historyOn, incognito, fieldSensitive, markedSensitive)

    @Test
    fun `a clip from the phone is on the panel, newest first`() {
        assertTrue(keep("first"))
        assertTrue(PairedClips.keep(history, "second", 2_000L, historyOn = true, incognito = false, fieldSensitive = false))
        assertEquals(listOf("second", "first"), history.entries.map { it.text })
    }

    @Test
    fun `a clip marked sensitive is never stored`() {
        assertFalse(keep("secret", markedSensitive = true))
        assertEquals(0, history.size)
    }

    @Test
    fun `nothing is stored while incognito, with the history off, or beside a password field`() {
        assertFalse(keep("a", incognito = true))
        assertFalse(keep("b", historyOn = false))
        assertFalse(keep("c", fieldSensitive = true))
        assertEquals(0, history.size)
    }

    @Test
    fun `a blank clip or one over the history's limit is not stored`() {
        assertFalse(keep("   "))
        assertFalse(keep("x".repeat(5001)))
        assertEquals(0, history.size)
    }

    @Test
    fun `the same clip again moves to the top and keeps its pin`() {
        keep("note")
        history.setPinned("note", true)
        keep("other")
        assertTrue(keep("note"))
        assertEquals(2, history.size)
        assertTrue(history.entries.first { it.text == "note" }.pinned)
    }
}

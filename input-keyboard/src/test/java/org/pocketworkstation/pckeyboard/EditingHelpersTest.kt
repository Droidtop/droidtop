package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard's editing helpers as pure logic (Droidtop/tracker#340). */
class EditingHelpersTest {
    // --- clipboard history ---

    @Test fun historyListsPinnedFirstThenNewestFirst() {
        val h = ClipboardHistory()
        h.add("one", 1)
        h.add("two", 2)
        h.add("three", 3)
        h.setPinned("one", true)
        assertEquals(listOf("one", "three", "two"), h.entries.map { it.text })
    }

    @Test fun historyIsBoundedAndKeepsPinnedEntries() {
        val h = ClipboardHistory(maxUnpinned = 3)
        h.add("keep", 0)
        h.setPinned("keep", true)
        for (i in 1..10) h.add("clip $i", i.toLong())
        val texts = h.entries.map { it.text }
        assertEquals(listOf("keep", "clip 10", "clip 9", "clip 8"), texts)
    }

    @Test fun aRepeatMovesToTheTopAndKeepsItsPin() {
        val h = ClipboardHistory()
        h.add("a", 1)
        h.setPinned("a", true)
        h.add("b", 2)
        h.add("a", 3)
        assertEquals(2, h.size)
        assertTrue(h.entries.first { it.text == "a" }.pinned)
        assertEquals(3L, h.entries.first { it.text == "a" }.at)
    }

    @Test fun sensitiveBlankLongAndIncognitoClipsAreNotRecorded() {
        val h = ClipboardHistory(maxChars = 5)
        assertFalse(h.add("secret", 1, sensitive = true))
        assertFalse(h.add("   ", 1))
        assertFalse(h.add("toolong", 1))
        assertFalse(h.add("ok", 1, incognito = true))
        assertEquals(0, h.size)
        assertTrue(h.add("ok", 1))
    }

    @Test fun clearKeepsPinnedUnlessToldNotTo() {
        val h = ClipboardHistory()
        h.add("a", 1)
        h.add("b", 2)
        h.setPinned("a", true)
        h.clear()
        assertEquals(listOf("a"), h.entries.map { it.text })
        h.clear(keepPinned = false)
        assertEquals(0, h.size)
    }

    @Test fun historySurvivesTheFileFormatWithAwkwardText() {
        val h = ClipboardHistory()
        h.add("line one\nline two\ttabbed \\ back", 5)
        h.add("plain", 6)
        h.setPinned("plain", true)
        val back = ClipboardHistory()
        back.restore(h.encode())
        assertEquals(h.entries, back.entries)
    }

    @Test fun restoreSkipsLinesThatDoNotParse() {
        val h = ClipboardHistory()
        h.restore("garbage\n-\tnotanumber\tx\nP\t7\tgood\n")
        assertEquals(listOf("good"), h.entries.map { it.text })
    }

    // --- inline autofill ---

    @Test fun inlineAutofillNeedsAndroid11AndThePreference() {
        assertFalse(InlineRules.wanted(29, true))
        assertFalse(InlineRules.wanted(30, false))
        assertTrue(InlineRules.wanted(30, true))
        assertTrue(InlineRules.wanted(36, true))
    }

    // --- space drag ---

    @Test fun spaceDragIsATapUntilTheThresholdThenStepsTheCursor() {
        val drag = SpaceDrag(activatePx = 20, stepPx = 10)
        drag.down(100)
        assertEquals(0, drag.move(110))
        assertFalse(drag.active)
        assertEquals(0, drag.move(121))
        assertTrue(drag.active)
        assertEquals(0, drag.move(125))
        assertEquals(1, drag.move(131))
        assertEquals(2, drag.move(152))
        assertEquals(-2, drag.move(122))
    }

    @Test fun spaceDragLeftWardsStepsNegative() {
        val drag = SpaceDrag(20, 10)
        drag.down(100)
        assertEquals(0, drag.move(79))
        assertTrue(drag.active)
        assertEquals(-2, drag.move(59))
    }

    @Test fun theCompanionPanelDefersSpaceSoADragCanCancelIt() {
        val sent = ArrayList<Pair<Int, Boolean>>()
        val resolver = CharKeyResolver { if (it == ' ') KeyStroke(KeyEvent.KEYCODE_SPACE) else null }
        fun listener() = SecondScreenKeyboardListener({ code, down -> sent += code to down }, resolver, deferSpace = true)

        val tap = listener()
        tap.onPress(32)
        assertEquals(emptyList<Pair<Int, Boolean>>(), sent)
        tap.onKey(32, null, 0, 0)
        tap.onRelease(32)
        assertEquals(listOf(KeyEvent.KEYCODE_SPACE to true, KeyEvent.KEYCODE_SPACE to false), sent)

        sent.clear()
        val drag = listener()
        drag.onPress(32)
        drag.onCursorDrag(0)
        drag.onRelease(32)
        drag.onCursorDrag(2)
        drag.onCursorDrag(-1)
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        val left = KeyEvent.KEYCODE_DPAD_LEFT
        assertEquals(listOf(right to true, right to false, right to true, right to false, left to true, left to false), sent)
    }

    // --- incognito ---

    @Test fun learningIsOffInIncognitoPasswordsAndWhereTheEditorAsks() {
        assertTrue(IncognitoRules.learningAllowed(false, 0, false))
        assertFalse(IncognitoRules.learningAllowed(true, 0, false))
        assertFalse(IncognitoRules.learningAllowed(false, 0, true))
        assertFalse(IncognitoRules.learningAllowed(false, android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false))
    }

    // --- macros ---

    @Test fun aMacroLineParsesIntoChordsAndText() {
        val macro = MacroParser.parseLine("tmux window = C-b c")!!
        assertEquals("tmux window", macro.name)
        assertEquals(
            listOf(
                MacroStep.Chord(KeyEvent.KEYCODE_B, ctrl = true),
                MacroStep.Chord(KeyEvent.KEYCODE_C),
            ),
            macro.steps,
        )
        val vim = MacroParser.parseLine("quit = Esc \":wq\\n\"")!!
        assertEquals(MacroStep.Chord(KeyEvent.KEYCODE_ESCAPE), vim.steps[0])
        assertEquals(MacroStep.Text(":wq\n"), vim.steps[1])
    }

    @Test fun modifiersCombineAndCapitalsAreShifted() {
        val steps = MacroParser.parseSteps("C-A-Del S-Tab M-x F5 X")!!
        assertEquals(MacroStep.Chord(KeyEvent.KEYCODE_FORWARD_DEL, ctrl = true, alt = true), steps[0])
        assertEquals(MacroStep.Chord(KeyEvent.KEYCODE_TAB, shift = true), steps[1])
        assertEquals(MacroStep.Chord(KeyEvent.KEYCODE_X, meta = true), steps[2])
        assertEquals(MacroStep.Chord(KeyEvent.KEYCODE_F5), steps[3])
        assertEquals(MacroStep.Chord(KeyEvent.KEYCODE_X, shift = true), steps[4])
    }

    @Test fun aLineWithAnUnknownStepIsSkippedWholeAndCommentsAreIgnored() {
        val macros = MacroParser.parse("# a comment\n\nbad = C-b nonsense\ngood = C-b c\nno equals sign\nempty =\n")
        assertEquals(listOf("good"), macros.map { it.name })
        assertNull(MacroParser.parseSteps("\"unterminated"))
    }

    @Test fun aMacroIsDeliveredAsHardwareKeysInOrder() {
        val events = ArrayList<String>()
        val player = MacroPlayer({ code, down -> events += "${if (down) "+" else "-"}$code" }, { events += "text:$it" })
        player.play(MacroParser.parseLine("m = C-b c \"ls\\n\"")!!)
        val ctrl = KeyEvent.KEYCODE_CTRL_LEFT
        val b = KeyEvent.KEYCODE_B
        val c = KeyEvent.KEYCODE_C
        val enter = KeyEvent.KEYCODE_ENTER
        assertEquals(
            listOf("+$ctrl", "+$b", "-$b", "-$ctrl", "+$c", "-$c", "text:ls", "+$enter", "-$enter"),
            events,
        )
    }

    @Test fun modifiersAreReleasedInReverseOrder() {
        val events = ArrayList<String>()
        val player = MacroPlayer({ code, down -> events += "${if (down) "+" else "-"}$code" }, {})
        player.play(MacroParser.parseLine("m = C-A-S-x")!!)
        val order = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT)
        val x = KeyEvent.KEYCODE_X
        assertEquals(
            order.map { "+$it" } + listOf("+$x", "-$x") + order.reversed().map { "-$it" },
            events,
        )
    }
}

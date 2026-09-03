package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The second-screen keyboard's container route, as pure logic.
 *
 * The expectations below are checked against Hacker's Keyboard's own
 * constants (`LatinKeyboardView.KEYCODE_*`, `Keyboard.KEYCODE_*`) and
 * Android's `KeyEvent` constants, which is the point of the negation rule:
 * if the fork's encoding and Android's keycodes ever stopped agreeing,
 * these fail rather than the container receiving a different key than the
 * user pressed.
 */
class SecondScreenKeyboardTest {

    /**
     * Stands in for `KeyCharacterMap`. Only the handful of characters the
     * tests use, and the shift relationship, need to be real here -- the
     * platform's own map is what runs in production, precisely so that
     * this table never has to be complete.
     */
    private val fakeResolver = CharKeyResolver { character ->
        when (character) {
            'a' -> KeyStroke(KeyEvent.KEYCODE_A)
            'A' -> KeyStroke(KeyEvent.KEYCODE_A, shift = true)
            'c' -> KeyStroke(KeyEvent.KEYCODE_C)
            '1' -> KeyStroke(KeyEvent.KEYCODE_1)
            '!' -> KeyStroke(KeyEvent.KEYCODE_1, shift = true)
            else -> null
        }
    }

    private class Sink {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val send: (Int, Boolean) -> Unit = { code, down -> sent += code to down }
    }

    @Test
    fun `the fork encodes every special key as a negated Android keycode`() {
        // LatinKeyboardView's own constants, read back through the rule.
        assertEquals(KeyEvent.KEYCODE_ESCAPE, HackersKeyCodes.specialStroke(-111)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, HackersKeyCodes.specialStroke(-113)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, HackersKeyCodes.specialStroke(-57)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_META_LEFT, HackersKeyCodes.specialStroke(-117)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, HackersKeyCodes.specialStroke(-19)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, HackersKeyCodes.specialStroke(-22)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_F1, HackersKeyCodes.specialStroke(-131)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_F12, HackersKeyCodes.specialStroke(-142)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, HackersKeyCodes.specialStroke(-92)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, HackersKeyCodes.specialStroke(-112)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_CAPS_LOCK, HackersKeyCodes.specialStroke(-115)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_INSERT, HackersKeyCodes.specialStroke(-124)?.androidKeyCode)
    }

    @Test
    fun `the fork's own pre-negation codes are read as what they mean, not negated`() {
        // Keyboard.KEYCODE_DELETE is -5, and -5 negated would be
        // KEYCODE_MEDIA_PLAY_PAUSE, not Backspace.
        assertEquals(KeyEvent.KEYCODE_DEL, HackersKeyCodes.specialStroke(-5)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, HackersKeyCodes.specialStroke(-1)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_TAB, HackersKeyCodes.specialStroke(9)?.androidKeyCode)
        assertEquals(KeyEvent.KEYCODE_ENTER, HackersKeyCodes.specialStroke(10)?.androidKeyCode)
    }

    @Test
    fun `codes that address the keyboard itself reach no container`() {
        // Mode change, cancel, done, alt-sym, options, voice, language
        // cycling, compose: all meaningful to the keyboard, meaningless to
        // a compositor.
        for (code in listOf(-2, -3, -4, -6, -100, -101, -102, -103, -104, -105, -10024)) {
            assertNull("code $code should not reach the container", HackersKeyCodes.specialStroke(code))
        }
    }

    @Test
    fun `Fn and layout-switch change this keyboard and reach nothing else`() {
        val sink = Sink()
        var toggles = 0
        val listener = SecondScreenKeyboardListener(
            send = sink.send,
            resolver = fakeResolver,
            onLayoutToggle = { toggles++ },
        )

        listener.onPress(-119) // LatinKeyboardView.KEYCODE_FN
        listener.onRelease(-119)
        listener.onPress(-2) // Keyboard.KEYCODE_MODE_CHANGE
        listener.onRelease(-2)

        assertEquals(2, toggles)
        assertEquals(emptyList<Pair<Int, Boolean>>(), sink.sent)
    }

    @Test
    fun `a character no key produces is committed as text where there is a text channel`() {
        val sink = Sink()
        val committed = mutableListOf<String>()
        val listener = SecondScreenKeyboardListener(
            send = sink.send,
            resolver = fakeResolver,
            commit = { committed += it.toString() },
        )

        listener.onPress(0x00E9) // e-acute: no key on a us keymap produces it
        listener.onRelease(0x00E9)

        assertEquals(listOf("é"), committed)
        assertEquals(emptyList<Pair<Int, Boolean>>(), sink.sent)
    }

    @Test
    fun `with no text channel an unproducible character is dropped rather than guessed at`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        listener.onPress(0x00E9)
        listener.onRelease(0x00E9)

        assertEquals(emptyList<Pair<Int, Boolean>>(), sink.sent)
    }

    @Test
    fun `a printable key is held down for its whole press and released once`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        listener.onPress('a'.code)
        listener.onKey('a'.code, null, 0, 0)
        listener.onRelease('a'.code)

        assertEquals(
            listOf(KeyEvent.KEYCODE_A to true, KeyEvent.KEYCODE_A to false),
            sink.sent,
        )
    }

    @Test
    fun `a shifted character wraps the key in a shift press`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        listener.onPress('!'.code)
        listener.onRelease('!'.code)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SHIFT_LEFT to true,
                KeyEvent.KEYCODE_1 to true,
                KeyEvent.KEYCODE_1 to false,
                KeyEvent.KEYCODE_SHIFT_LEFT to false,
            ),
            sink.sent,
        )
    }

    @Test
    fun `Ctrl latches, applies to the next key, and then lets go`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        // Tap Ctrl (the fork's -113), then tap C.
        listener.onPress(-113)
        listener.onRelease(-113)
        listener.onPress('c'.code)
        listener.onRelease('c'.code)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_CTRL_LEFT to true,
                KeyEvent.KEYCODE_C to true,
                KeyEvent.KEYCODE_C to false,
                KeyEvent.KEYCODE_CTRL_LEFT to false,
            ),
            sink.sent,
        )
    }

    @Test
    fun `tapping a latched modifier a second time unlatches it`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        listener.onPress(-113)
        listener.onRelease(-113)
        listener.onPress(-113)
        listener.onRelease(-113)
        listener.onPress('c'.code)
        listener.onRelease('c'.code)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_CTRL_LEFT to true,
                KeyEvent.KEYCODE_CTRL_LEFT to false,
                KeyEvent.KEYCODE_C to true,
                KeyEvent.KEYCODE_C to false,
            ),
            sink.sent,
        )
    }

    @Test
    fun `a latched shift is not doubled by a shifted character`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        listener.onPress(-1) // the fork's Shift key
        listener.onRelease(-1)
        listener.onPress('!'.code)
        listener.onRelease('!'.code)

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SHIFT_LEFT to true,
                KeyEvent.KEYCODE_1 to true,
                KeyEvent.KEYCODE_1 to false,
                KeyEvent.KEYCODE_SHIFT_LEFT to false,
            ),
            sink.sent,
        )
    }

    @Test
    fun `auto-repeat does not stack a second press on the compositor's own repeat`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        listener.onPress(-5) // Backspace
        repeat(10) { listener.onKey(-5, null, 0, 0) }
        listener.onRelease(-5)

        assertEquals(
            listOf(KeyEvent.KEYCODE_DEL to true, KeyEvent.KEYCODE_DEL to false),
            sink.sent,
        )
    }

    @Test
    fun `releaseEverything lets go of a latched modifier and a held key`() {
        val sink = Sink()
        val listener = SecondScreenKeyboardListener(sink.send, fakeResolver)

        listener.onPress(-113)
        listener.onRelease(-113)
        listener.onPress('a'.code)
        sink.sent.clear()

        listener.releaseEverything()

        assertEquals(
            listOf(
                KeyEvent.KEYCODE_A to false,
                KeyEvent.KEYCODE_CTRL_LEFT to false,
            ),
            sink.sent,
        )
    }
}

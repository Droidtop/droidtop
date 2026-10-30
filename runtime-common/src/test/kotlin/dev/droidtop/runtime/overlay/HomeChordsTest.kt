package dev.droidtop.runtime.overlay

import android.view.KeyEvent
import dev.droidtop.runtime.overlay.HomeChords.Decision
import dev.droidtop.runtime.overlay.OverlayRequest.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HOME chords (docs/SPEC.md "In-game overlays", Droidtop/tracker#740). */
class HomeChordsTest {
    private val chords = HomeChords()
    private var overlayOpen = false

    private fun down(code: Int, pad: Boolean = true, repeat: Int = 0) = chords.onKey(code, true, repeat, pad, overlayOpen)
    private fun up(code: Int, pad: Boolean = true) = chords.onKey(code, false, 0, pad, overlayOpen)

    private val pass = Decision(consume = false)
    private val eat = Decision(consume = true)

    @Test
    fun `HOME with R2, L2 or Start opens that overlay, and both keys are eaten`() {
        listOf(KeyEvent.KEYCODE_BUTTON_R2 to Kind.QUICK_MENU, KeyEvent.KEYCODE_BUTTON_L2 to Kind.CONTEXT_MENU, KeyEvent.KEYCODE_BUTTON_START to Kind.LEFT_MENU)
            .forEach { (button, kind) ->
                assertEquals(eat, down(KeyEvent.KEYCODE_HOME))
                assertEquals(Decision(consume = true, open = kind), down(button))
                assertEquals(eat, up(button))
                // Not HOME on its own: the chord used it.
                assertEquals(eat, up(KeyEvent.KEYCODE_HOME))
            }
    }

    @Test
    fun `HOME alone is HOME, and a held one does not repeat it`() {
        assertEquals(eat, down(KeyEvent.KEYCODE_HOME))
        assertEquals(eat, down(KeyEvent.KEYCODE_HOME, repeat = 1))
        assertEquals(Decision(consume = true, home = true), up(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `a chord button let go after HOME is still eaten, and a held one opens once`() {
        down(KeyEvent.KEYCODE_HOME)
        assertEquals(Decision(consume = true, open = Kind.QUICK_MENU), down(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(eat, down(KeyEvent.KEYCODE_BUTTON_R2, repeat = 1))
        assertEquals(eat, up(KeyEvent.KEYCODE_HOME))
        assertEquals(eat, up(KeyEvent.KEYCODE_BUTTON_R2))
        // After that, R2 is the app's again.
        assertEquals(pass, down(KeyEvent.KEYCODE_BUTTON_R2))
    }

    @Test
    fun `every other key goes on at once, HOME held or not`() {
        assertEquals(pass, down(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(pass, up(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(pass, down(KeyEvent.KEYCODE_A, pad = false))
        down(KeyEvent.KEYCODE_HOME)
        assertEquals(pass, down(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(pass, up(KeyEvent.KEYCODE_BUTTON_A))
        // A non-chord key under HOME leaves HOME as HOME.
        assertEquals(Decision(consume = true, home = true), up(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `a HOME release whose press was not held is Android's`() {
        assertEquals(pass, up(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `while an overlay is open the pad goes to it, each press with its release`() {
        overlayOpen = true
        assertEquals(Decision(consume = true, toOverlay = true), down(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(Decision(consume = true, toOverlay = true), up(KeyEvent.KEYCODE_DPAD_DOWN))
        // A keyboard is still the app's.
        assertEquals(pass, down(KeyEvent.KEYCODE_A, pad = false))
        // B pressed in the overlay closes it; its release reaches nobody, not the app.
        assertEquals(Decision(consume = true, toOverlay = true), down(KeyEvent.KEYCODE_BUTTON_B))
        overlayOpen = false
        assertEquals(eat, up(KeyEvent.KEYCODE_BUTTON_B))
        // The same chord again asks again; the overlay closes on it.
        overlayOpen = true
        down(KeyEvent.KEYCODE_HOME)
        assertEquals(Decision(consume = true, open = Kind.QUICK_MENU), down(KeyEvent.KEYCODE_BUTTON_R2))
    }

    @Test
    fun `a vendor Home key can stand in for HOME`() {
        val vendor = HomeChords(homeKeys = setOf(KeyEvent.KEYCODE_BUTTON_MODE))
        assertEquals(eat, vendor.onKey(KeyEvent.KEYCODE_BUTTON_MODE, true, 0, true, false))
        assertEquals(Decision(consume = true, open = Kind.LEFT_MENU), vendor.onKey(KeyEvent.KEYCODE_BUTTON_START, true, 0, true, false))
        assertEquals(pass, vendor.onKey(KeyEvent.KEYCODE_HOME, true, 0, false, false))
    }

    private val keys = listOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_LEFT,
    )

    @Test
    fun `fast, interleaved and lost-release sequences always come back to idle`() {
        val random = java.util.Random(740)
        repeat(2_000) { round ->
            val chords = HomeChords()
            var time = 0L
            var open = false
            repeat(1 + random.nextInt(40)) {
                val code = keys[random.nextInt(keys.size)]
                val down = random.nextBoolean()
                val repeatCount = if (down && random.nextInt(5) == 0) 1 + random.nextInt(3) else 0
                // Presses a few milliseconds apart, as fast as a thumb on a pad, now and then a pause.
                time += if (random.nextInt(10) == 0) random.nextInt(8_000) else random.nextInt(30)
                if (random.nextInt(8) == 0) open = !open
                chords.onKey(code, down, repeatCount, fromPad = code != KeyEvent.KEYCODE_HOME, overlayOpen = open, eventTime = time)
            }
            // The person presses and lets go of every key once more: whatever releases were lost, nothing stays held.
            for (code in keys) {
                time += 20
                chords.onKey(code, true, 0, code != KeyEvent.KEYCODE_HOME, open, time)
                time += 20
                chords.onKey(code, false, 0, code != KeyEvent.KEYCODE_HOME, open, time)
            }
            assertTrue("round $round did not come back to idle", chords.idle)
        }
    }

    @Test
    fun `a chord still opens after its earlier HOME release was lost`() {
        assertEquals(eat, chords.onKey(KeyEvent.KEYCODE_HOME, true, 0, false, false, eventTime = 1_000))
        // The release never came. Much later, R2 alone is the game's.
        assertEquals(pass, chords.onKey(KeyEvent.KEYCODE_BUTTON_R2, true, 0, true, false, eventTime = 1_000 + HomeChords.STALE_HOLD_MS + 1))
        assertEquals(pass, chords.onKey(KeyEvent.KEYCODE_BUTTON_R2, false, 0, true, false, eventTime = 1_000 + HomeChords.STALE_HOLD_MS + 2))
        // And the next chord works.
        assertEquals(eat, chords.onKey(KeyEvent.KEYCODE_HOME, true, 0, false, false, eventTime = 20_000))
        assertEquals(Decision(consume = true, open = Kind.QUICK_MENU), chords.onKey(KeyEvent.KEYCODE_BUTTON_R2, true, 0, true, false, eventTime = 20_010))
    }

    @Test
    fun `a chord button whose release was lost goes back to the game on its next press`() {
        assertEquals(eat, down(KeyEvent.KEYCODE_HOME))
        assertEquals(Decision(consume = true, open = Kind.QUICK_MENU), down(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(eat, up(KeyEvent.KEYCODE_HOME))
        // R2's release was lost. Its next press and release are the game's, both of them.
        assertEquals(pass, down(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(pass, up(KeyEvent.KEYCODE_BUTTON_R2))
        assertTrue(chords.idle)
    }

    @Test
    fun `reset forgets a held HOME and owed releases`() {
        down(KeyEvent.KEYCODE_HOME)
        down(KeyEvent.KEYCODE_BUTTON_L2)
        chords.reset()
        assertTrue(chords.idle)
        assertEquals(pass, down(KeyEvent.KEYCODE_BUTTON_R2))
    }
}

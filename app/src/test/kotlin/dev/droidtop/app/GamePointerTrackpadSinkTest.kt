package dev.droidtop.app

import dev.droidtop.input.EvdevKeys
import dev.droidtop.input.PointerAcceleration
import dev.droidtop.input.TRACKPAD_SCROLL_MM_PER_DETENT
import dev.droidtop.input.TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH
import dev.droidtop.library.OwnGameScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's trackpad as a Windows game's mouse (Droidtop/tracker#565): scale, carried fractions, buttons, wheel. */
class GamePointerTrackpadSinkTest {
    private class Recorder(override val widthPx: Int = 1600) : OwnGameScreens.Pointer {
        override val displayId: Int? = 0
        var x = 0
        var y = 0
        val buttons = mutableListOf<Pair<OwnGameScreens.PointerButton, Boolean>>()
        val wheel = mutableListOf<Boolean>()
        override fun move(dx: Int, dy: Int) { x += dx; y += dy }
        override fun button(button: OwnGameScreens.PointerButton, pressed: Boolean) { buttons += button to pressed }
        override fun wheel(down: Boolean) { wheel += down }
    }

    @Test
    fun `a trackpad's travel is scaled to the game's width, and slow movement is not lost`() {
        val game = Recorder()
        // Slow: many tiny moves that are each under a pixel, at the precision end of the curve.
        val sink = GamePointerTrackpadSink({ game }, userSpeed = 0f, naturalScroll = true)
        val steps = 1600
        repeat(steps) { sink.onMove(TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH / steps, 0f, 0f) }
        val expected = game.widthPx * PointerAcceleration.factor(0f, 0f)
        assertTrue("moved ${game.x}, expected about $expected", kotlin.math.abs(game.x - expected) <= 2f)
    }

    @Test
    fun `buttons map to the game's, and two fingers scroll in whole wheel steps`() {
        val game = Recorder()
        val sink = GamePointerTrackpadSink({ game }, userSpeed = 0f, naturalScroll = true)
        sink.onButton(EvdevKeys.BTN_LEFT, true)
        sink.onButton(EvdevKeys.BTN_LEFT, false)
        sink.onButton(EvdevKeys.BTN_RIGHT, true)
        assertEquals(
            listOf(
                OwnGameScreens.PointerButton.LEFT to true, OwnGameScreens.PointerButton.LEFT to false,
                OwnGameScreens.PointerButton.RIGHT to true,
            ),
            game.buttons,
        )
        // Natural scrolling: fingers moving up (negative y) scroll the content down.
        sink.onScroll(0f, -TRACKPAD_SCROLL_MM_PER_DETENT * 2.5f)
        assertEquals(listOf(true, true), game.wheel)
        sink.onScroll(0f, -TRACKPAD_SCROLL_MM_PER_DETENT * 0.5f)
        assertEquals("the half step carried over", listOf(true, true, true), game.wheel)
    }

    @Test
    fun `with no game screen it does nothing`() {
        val sink = GamePointerTrackpadSink({ null }, userSpeed = 0f, naturalScroll = false)
        sink.onMove(10f, 10f, 100f)
        sink.onButton(EvdevKeys.BTN_LEFT, true)
        sink.onScroll(0f, 30f)
    }
}

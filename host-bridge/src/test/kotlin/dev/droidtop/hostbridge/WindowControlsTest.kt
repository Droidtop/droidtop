package dev.droidtop.hostbridge

import dev.droidtop.hostbridge.WindowControls.Companion.BOTTOM
import dev.droidtop.hostbridge.WindowControls.Companion.LEFT
import dev.droidtop.hostbridge.WindowControls.Companion.RIGHT
import dev.droidtop.hostbridge.WindowControls.Companion.TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surface is 1920 x 1080 view pixels showing a 960 x 540 output (Desktop scale 2), and a press target is 96 view
 * pixels (48dp at density 2), unless a test says otherwise.
 */
class WindowControlsTest {
    private val output = "HEADLESS-1"
    private val box = SwayRect(0, 0, 960, 540)

    /** A window whose rect is [x], [y], [w], [h] and whose title bar, [title] high, is right above it. */
    private fun window(id: Long, x: Int, y: Int, w: Int, h: Int, title: Int = 24, border: Int = 8, floating: Boolean = true) =
        WindowFrame(
            conId = id, appId = "app$id", title = "t$id", output = output, floating = floating, focused = false,
            rect = SwayRect(x, y, w, h), titleBar = if (title > 0) SwayRect(x, y - title, w, title) else null, border = border,
        )

    private fun controls(vararg windows: WindowFrame, view: Pair<Int, Int> = 1920 to 1080, target: Float = 96f, outputBox: SwayRect = box) =
        WindowControls.layout(SwayFrames(mapOf(output to outputBox), windows.toList()), output, view.first, view.second, target)

    private val foot = window(11, 100, 124, 400, 300)

    @Test
    fun `three buttons at the right end of the title bar, close last, drawn title-bar high in finger-wide slots`() {
        val buttons = controls(foot).buttons
        assertEquals(listOf(WindowButton.MINIMIZE, WindowButton.MAXIMIZE, WindowButton.CLOSE), buttons.map { it.kind })
        val close = buttons.last()
        assertEquals(ViewBox(928f, 200f, 976f, 248f), close.visual)
        // The press area is the whole 96 px slot, 96 px high, centred on the 48 px title bar.
        assertEquals(ViewBox(904f, 176f, 1000f, 272f), close.press)
        assertEquals(ViewBox(712f, 176f, 808f, 272f), buttons.first().press)
    }

    @Test
    fun `a press on a button, on its visual or anywhere in its press area, is that button`() {
        val controls = controls(foot)
        assertEquals(WindowButton.CLOSE, (controls.hit(950f, 220f) as ControlHit.Button).button.kind)
        assertEquals(WindowButton.CLOSE, (controls.hit(905f, 270f) as ControlHit.Button).button.kind)
        assertEquals(WindowButton.MAXIMIZE, (controls.hit(850f, 180f) as ControlHit.Button).button.kind)
        assertEquals(WindowButton.MINIMIZE, controls.buttonAt(720f, 230f)?.kind)
    }

    @Test
    fun `the title bar outside the buttons and the window's content stay the compositor's`() {
        val controls = controls(foot)
        assertNull(controls.hit(600f, 220f))
        assertNull(controls.hit(600f, 500f))
        assertNull(controls.hit(250f, 260f))
    }

    @Test
    fun `the band is the border inside and the rest of a press target outside, on every edge`() {
        val controls = controls(foot)
        // Frame in view pixels: 200..1000 across, 200..848 down; the 8 px border is 16 view pixels; outside, 80.
        assertEquals(ControlHit.Resize(11, LEFT, 204f, 500f), controls.hit(204f, 500f))
        assertEquals(ControlHit.Resize(11, LEFT, 202f, 500f), controls.hit(130f, 500f))
        assertNull(controls.hit(119f, 500f))
        assertEquals(ControlHit.Resize(11, RIGHT, 998f, 500f), controls.hit(1050f, 500f))
        assertEquals(ControlHit.Resize(11, BOTTOM, 600f, 846f), controls.hit(600f, 900f))
        assertEquals(ControlHit.Resize(11, BOTTOM, 600f, 840f), controls.hit(600f, 840f))
        assertEquals(ControlHit.Resize(11, TOP, 600f, 202f), controls.hit(600f, 150f))
        // A title bar's top has no band inside: it moves the window.
        assertNull(controls.hit(600f, 202f))
    }

    @Test
    fun `near a corner along either edge is the corner`() {
        val controls = controls(foot)
        assertEquals(LEFT or BOTTOM, (controls.hit(150f, 800f) as ControlHit.Resize).edges)
        assertEquals(LEFT or BOTTOM, (controls.hit(250f, 870f) as ControlHit.Resize).edges)
        assertEquals(RIGHT or TOP, (controls.hit(1040f, 160f) as ControlHit.Resize).edges)
        assertEquals(ControlHit.Resize(11, RIGHT or BOTTOM, 998f, 846f), controls.hit(1060f, 900f))
        // Mid-edge is the edge alone.
        assertEquals(LEFT, (controls.hit(150f, 520f) as ControlHit.Resize).edges)
    }

    @Test
    fun `a button's press area beats the band above the title bar, and a drawn button beats any band`() {
        val controls = controls(foot)
        assertEquals(WindowButton.CLOSE, (controls.hit(950f, 180f) as ControlHit.Button).button.kind)
        // A window right of foot's title bar: its band reaches over foot's close button, which still wins where drawn.
        val beside = window(12, 505, 124, 200, 200)
        val both = controls(foot, beside)
        assertEquals(WindowButton.CLOSE, (both.hit(970f, 220f) as ControlHit.Button).button.kind)
        // Off the drawn button, inside its press area, the window above's band wins.
        assertEquals(12L, (both.hit(998f, 265f) as ControlHit.Resize).conId)
    }

    @Test
    fun `a button under a window above is not drawn, and a press there is that window's`() {
        // A pixel-bordered window over foot's close button (view 880..1080 across, 180..380 down).
        val over = window(15, 440, 90, 100, 100, title = 0, border = 2)
        val controls = controls(foot, over)
        assertEquals(listOf(WindowButton.MINIMIZE, WindowButton.MAXIMIZE), controls.buttons.map { it.kind })
        assertNull(controls.hit(950f, 220f))
        // Its own border is its band (2 px is 4 view pixels), and so is the band outside it.
        assertEquals(15L, (controls.hit(882f, 300f) as ControlHit.Resize).conId)
    }

    @Test
    fun `a floating window's band reaches over a tiled window below it, never over a window beside it`() {
        val left = window(1, 0, 24, 480, 516, border = 2, floating = false)
        val right = window(2, 480, 24, 480, 516, border = 2, floating = false)
        val controls = controls(left, right, foot)
        // Inside the tiled window on the left, just outside foot: foot's band.
        assertEquals(11L, (controls.hit(150f, 500f) as ControlHit.Resize).conId)
        // Two tiled windows meet at 960 (below foot and its band): each one's own border is its band; past that, the
        // neighbour's content.
        assertEquals(ControlHit.Resize(2, LEFT, 962f, 950f), controls.hit(962f, 950f))
        assertEquals(ControlHit.Resize(1, RIGHT, 958f, 950f), controls.hit(958f, 950f))
        assertNull(controls.hit(970f, 950f))
        assertNull(controls.hit(950f, 950f))
    }

    @Test
    fun `a narrow title bar gets close alone, a narrower one none, and a tiny window still has its band`() {
        // 120 logical pixels is 240 view pixels: room for close and a slot of title, not for three.
        assertEquals(listOf(WindowButton.CLOSE), controls(window(3, 100, 124, 120, 100)).buttons.map { it.kind })
        assertEquals(emptyList<ControlButton>(), controls(window(4, 100, 124, 80, 100)).buttons)
        val tiny = controls(window(5, 300, 300, 10, 10))
        // 600..620 across, 552..620 down with its title bar; left of it, mid-height: left, and the nearer corner only.
        val edges = (tiny.hit(560f, 590f) as ControlHit.Resize).edges
        assertTrue((edges and LEFT) != 0)
        assertEquals(1, listOf(TOP, BOTTOM).count { (edges and it) != 0 })
        assertTrue((edges and RIGHT) == 0)
    }

    @Test
    fun `no buttons off the surface, and none without a title bar`() {
        assertEquals(emptyList<ControlButton>(), controls(window(6, 100, 10, 400, 300, title = 24)).buttons)
        assertEquals(emptyList<ControlButton>(), controls(window(7, 100, 124, 400, 300, title = 0, border = 2)).buttons)
    }

    @Test
    fun `the output's place in the layout and the surface's pixels per logical pixel are taken in`() {
        val second = SwayRect(960, 0, 800, 600)
        val frames = SwayFrames(
            mapOf("HEADLESS-1" to box, "HEADLESS-2" to second),
            listOf(window(11, 100, 124, 400, 300), window(21, 1060, 124, 400, 300).copy(output = "HEADLESS-2")),
        )
        // 1600 x 1200 showing 800 x 600: 2 view pixels a logical pixel, from the output's left edge at 960.
        val on2 = WindowControls.layout(frames, "HEADLESS-2", 1600, 1200, 96f)
        assertEquals(listOf(21L, 21L, 21L), on2.buttons.map { it.window.conId })
        assertEquals(ViewBox(928f, 200f, 976f, 248f), on2.buttons.last().visual)
        // A lower render resolution or another scale: each axis by its own pixels per logical pixel.
        val small = WindowControls.layout(frames, "HEADLESS-1", 1440, 810, 72f)
        assertEquals(ViewBox(696f, 150f, 732f, 186f), small.buttons.last().visual)
        // An unnamed primary with two outputs, or a name not in the tree: nothing.
        assertEquals(emptyList<ControlButton>(), WindowControls.layout(frames, "", 1920, 1080, 96f).buttons)
        assertNull(WindowControls.layout(frames, "HEADLESS-9", 1920, 1080, 96f).hit(950f, 220f))
    }

    @Test
    fun `nothing at all with no frames, as under labwc`() {
        val none = WindowControls.layout(SwayFrames.NONE, null, 1920, 1080, 96f)
        assertEquals(emptyList<ControlButton>(), none.buttons)
        assertNull(none.hit(10f, 10f))
    }
}

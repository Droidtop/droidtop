package dev.droidtop.hostbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pointer input on a named compositor output (Droidtop/tracker#908, step D1b). */
class OutputLayoutTest {
    private fun output(
        name: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Double = 1.0,
        transform: Int = 0,
        enabled: Boolean = true,
    ) = OutputPlacement(name, enabled, x, y, width, height, transform, scale)

    /** Where wlroots puts the pointer for [motion]: wlr_virtual_pointer_v1.c's x / x_extent onto the layout box. */
    private fun landsAt(placements: List<OutputPlacement>, motion: AbsoluteMotion): Pair<Double, Double> {
        val layout = OutputLayout.layoutBox(placements)!!
        return Pair(
            layout.x + layout.width * (motion.x.toDouble() / motion.extentWidth),
            layout.y + layout.height * (motion.y.toDouble() / motion.extentHeight),
        )
    }

    private fun assertLands(expectedX: Double, expectedY: Double, actual: Pair<Double, Double>) {
        assertEquals(expectedX, actual.first, 1e-6)
        assertEquals(expectedY, actual.second, 1e-6)
    }

    private val sideBySide = listOf(
        output("HEADLESS-1", 0, 0, 1920, 1080),
        output("HEADLESS-2", 1920, 0, 1280, 720),
    )

    @Test
    fun `one output is the whole layout, so a point lands where it is on the surface`() {
        val placements = listOf(output("HEADLESS-1", 0, 0, 1920, 1080))
        val motion = OutputLayout.absoluteMotion(placements, "HEADLESS-1", 960.0, 540.0, 1920, 1080)!!
        assertLands(960.0, 540.0, landsAt(placements, motion))
        assertLands(0.0, 0.0, landsAt(placements, OutputLayout.absoluteMotion(placements, "HEADLESS-1", 0.0, 0.0, 1920, 1080)!!))
    }

    @Test
    fun `a point on the second output lands on that output, not across the whole layout`() {
        val motion = OutputLayout.absoluteMotion(sideBySide, "HEADLESS-2", 640.0, 360.0, 1280, 720)!!
        assertLands(2560.0, 360.0, landsAt(sideBySide, motion))
        assertEquals(LayoutBox(0, 0, 3200, 1080), OutputLayout.layoutBox(sideBySide))
    }

    @Test
    fun `the first output keeps its own part of a larger layout`() {
        val motion = OutputLayout.absoluteMotion(sideBySide, "HEADLESS-1", 1919.0, 1079.0, 1920, 1080)!!
        assertLands(1919.0, 1079.0, landsAt(sideBySide, motion))
    }

    @Test
    fun `the surface's size need not be the output's`() {
        // A 2560x1440 surface showing a scale-2 output, 1280x720 logical, right of the first.
        val placements = listOf(output("HEADLESS-1", 0, 0, 1920, 1080), output("HEADLESS-2", 1920, 0, 2560, 1440, scale = 2.0))
        val motion = OutputLayout.absoluteMotion(placements, "HEADLESS-2", 1280.0, 720.0, 2560, 1440)!!
        assertLands(2560.0, 360.0, landsAt(placements, motion))
    }

    @Test
    fun `outputs stacked vertically and left of the origin`() {
        val placements = listOf(
            output("HEADLESS-1", 0, 0, 1920, 1080),
            output("HEADLESS-2", -1280, 1080, 1280, 720),
        )
        assertEquals(LayoutBox(-1280, 0, 3200, 1800), OutputLayout.layoutBox(placements))
        val motion = OutputLayout.absoluteMotion(placements, "HEADLESS-2", 320.0, 180.0, 1280, 720)!!
        assertLands(-960.0, 1260.0, landsAt(placements, motion))
    }

    @Test
    fun `an output's box follows wlroots, rotated and divided by a fractional scale`() {
        assertEquals(LayoutBox(0, 0, 1920, 1080), output("A", 0, 0, 1080, 1920, transform = 1).box())
        assertEquals(LayoutBox(0, 0, 1080, 1920), output("A", 0, 0, 1080, 1920, transform = 2).box())
        assertEquals(LayoutBox(0, 0, 1920, 1080), output("A", 0, 0, 1080, 1920, transform = 7).box())
        // 1366 / 1.5 = 910.67 and 768 / 1.5 = 512: truncated, as int /= float truncates.
        assertEquals(LayoutBox(5, 6, 910, 512), output("A", 5, 6, 1366, 768, scale = 1.5).box())
    }

    @Test
    fun `a point off the surface stays on the output's edge`() {
        val right = OutputLayout.absoluteMotion(sideBySide, "HEADLESS-1", 5000.0, -40.0, 1920, 1080)!!
        val (x, y) = landsAt(sideBySide, right)
        assertTrue("x $x is still on HEADLESS-1", x < 1920.0 && x > 1919.0)
        assertEquals(0.0, y, 1e-9)
        val left = OutputLayout.absoluteMotion(sideBySide, "HEADLESS-2", -3.0, Double.NaN, 1280, 720)!!
        assertLands(1920.0, 0.0, landsAt(sideBySide, left))
    }

    @Test
    fun `a disabled output is no part of the layout and takes no pointer`() {
        val placements = sideBySide + output("HEADLESS-3", 3200, 0, 800, 600, enabled = false)
        assertEquals(LayoutBox(0, 0, 3200, 1080), OutputLayout.layoutBox(placements))
        assertNull(OutputLayout.absoluteMotion(placements, "HEADLESS-3", 1.0, 1.0, 800, 600))
    }

    @Test
    fun `nothing is sent for an unknown output, an output of unknown size or an empty surface`() {
        assertNull(OutputLayout.absoluteMotion(sideBySide, "HEADLESS-9", 1.0, 1.0, 100, 100))
        assertNull(OutputLayout.absoluteMotion(sideBySide, "", 1.0, 1.0, 100, 100))
        assertNull(OutputLayout.absoluteMotion(listOf(output("HEADLESS-1", 0, 0, 0, 0)), "HEADLESS-1", 1.0, 1.0, 100, 100))
        assertNull(OutputLayout.absoluteMotion(sideBySide, "HEADLESS-2", 1.0, 1.0, 0, 720))
        assertNull(OutputLayout.layoutBox(emptyList()))
    }

    @Test
    fun `a huge layout and surface stay within the protocol's uint32 and still land right`() {
        val placements = listOf(output("A", 0, 0, 16384, 16384), output("B", 16384, 0, 16384, 16384))
        val motion = OutputLayout.absoluteMotion(placements, "B", 8000.0, 4000.0, 16000, 16000)!!
        assertTrue(motion.extentWidth <= OutputLayout.MAX_PROTOCOL_VALUE)
        assertTrue(motion.x < motion.extentWidth)
        val (x, y) = landsAt(placements, motion)
        assertEquals(16384.0 + 8192.0, x, 0.01)
        assertEquals(4096.0, y, 0.01)
    }
}

package dev.droidtop.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** The stick test's line (Droidtop/tracker#723), at the default dead zone's 0.5 / 0.3 band. */
class StickLineTest {
    private fun line(x: Float, y: Float) = stickLine(x, y, maxOf(kotlin.math.abs(x), kotlin.math.abs(y)), 0.5f, 0.3f)

    @Test
    fun `a centred stick, drift, the margin and a real lean each say so`() {
        assertTrue(line(0f, 0f).endsWith("centred"))
        assertTrue(line(0.12f, 0f).contains("drift"))
        assertTrue(line(0f, -0.4f).contains("margin"))
        assertTrue(line(0f, -0.9f).endsWith("moves the menus"))
    }
}

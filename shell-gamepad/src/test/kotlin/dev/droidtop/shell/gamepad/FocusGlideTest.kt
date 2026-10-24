package dev.droidtop.shell.gamepad

import dev.droidtop.ui.shared.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sliding ring's decisions (FocusGlide.kt, docs/SPEC.md "Gaming motion
 * and focus"): which edge leads a move, when two moves are a held
 * direction, and when a move travels as a drop.
 */
class FocusGlideTest {
    @Test
    fun theEdgeOnTheSideOfTheMoveLeads() {
        assertEquals(2, glideLeadEdge(dx = 120f, dy = 10f)) // right
        assertEquals(0, glideLeadEdge(dx = -120f, dy = 10f)) // left
        assertEquals(3, glideLeadEdge(dx = 5f, dy = 80f)) // down
        assertEquals(1, glideLeadEdge(dx = 5f, dy = -80f)) // up
    }

    @Test
    fun aDiagonalMoveIsLedHorizontallyOnATie() {
        assertEquals(2, glideLeadEdge(dx = 50f, dy = 50f))
    }

    @Test
    fun movesCloseTogetherAreAHeldDirection() {
        assertTrue(glideIsHeld(Motion.GlideRepeatMs - 1L))
        assertFalse(glideIsHeld(Motion.GlideRepeatMs.toLong()))
        assertFalse(glideIsHeld(1_000L))
    }

    @Test
    fun onlyAFarSinglePressTravelsAsADrop() {
        assertTrue(glideIsFar(held = false, distanceDp = Motion.GlideFarDp + 1f))
        assertFalse(glideIsFar(held = false, distanceDp = Motion.GlideFarDp - 1f))
        // A held direction never drops, however far each step is.
        assertFalse(glideIsFar(held = true, distanceDp = Motion.GlideFarDp * 3f))
    }

    @Test
    fun theDistanceIsEdgeToEdgeSoNeighboursGlide() {
        // A wide card and the small card beside it, 12 apart: their centres are 400 apart, their edges 12.
        val wide = androidx.compose.ui.geometry.Rect(0f, 0f, 600f, 300f)
        val next = androidx.compose.ui.geometry.Rect(612f, 0f, 812f, 300f)
        assertEquals(12f, glideGap(wide, next))
        assertEquals(12f, glideGap(next, wide))
        // Touching or overlapping is no distance at all; diagonal apart is the corner-to-corner length.
        assertEquals(0f, glideGap(wide, androidx.compose.ui.geometry.Rect(300f, 100f, 900f, 400f)))
        assertEquals(5f, glideGap(wide, androidx.compose.ui.geometry.Rect(603f, 304f, 700f, 400f)))
    }
}

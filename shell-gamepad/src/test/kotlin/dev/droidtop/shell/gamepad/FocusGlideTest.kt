package dev.droidtop.shell.gamepad

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
}

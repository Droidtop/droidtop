package dev.droidtop.shell.gamepad

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grow-out transitions' one rule as numbers (docs/SPEC.md "Gaming motion and focus"): the edge
 * with farthest to go leads, every other edge in proportion, and an edge already past the box's own
 * has nowhere to go.
 */
class PageFloodTest {
    private val box = Size(1000f, 500f)

    @Test
    fun theFarthestEdgeLeads() {
        // A capsule near the top-left: its right edge has the farthest to travel.
        val capsule = Rect(left = 100f, top = 50f, right = 200f, bottom = 200f)
        assertEquals(1f, floodLead(capsule, box, 2), 0.001f)
        assertEquals(100f / 800f, floodLead(capsule, box, 0), 0.001f)
        assertEquals(50f / 800f, floodLead(capsule, box, 1), 0.001f)
        assertEquals(300f / 800f, floodLead(capsule, box, 3), 0.001f)
    }

    @Test
    fun anEdgeOutsideTheBoxHasNowhereToGo() {
        val offTop = Rect(left = 400f, top = -20f, right = 600f, bottom = 100f)
        assertEquals(0f, floodLead(offTop, box, 1), 0.001f)
    }

    @Test
    fun aControlFillingTheBoxStillGivesFiniteLeads() {
        val all = Rect(0f, 0f, 1000f, 500f)
        (0 until 4).forEach { assertEquals(0f, floodLead(all, box, it), 0.001f) }
    }
}

package dev.droidtop.shell.gamepad.hosted

import org.junit.Assert.assertEquals
import org.junit.Test

/** Left and Right between a hosted sheet's sections (the Desktop Start menu's Pinned, Recent and All). */
class SectionJumpTest {
    private val sections = listOf("Pinned", "Pinned", "Recent", "Recent", "Recent", "All", "All")

    @Test
    fun rightGoesToTheNextSectionsFirstRowAndStopsAtTheLast() {
        assertEquals(2, sectionJump(sections, 0, forward = true))
        assertEquals(5, sectionJump(sections, 3, forward = true))
        assertEquals(6, sectionJump(sections, 6, forward = true))
    }

    @Test
    fun leftGoesToTheSectionsOwnFirstRowThenThePreviousSection() {
        assertEquals(2, sectionJump(sections, 4, forward = false))
        assertEquals(0, sectionJump(sections, 2, forward = false))
        assertEquals(0, sectionJump(sections, 0, forward = false))
    }

    @Test
    fun aListWithoutSectionsStaysPut() {
        assertEquals(1, sectionJump(listOf(null, null, null), 1, forward = true))
        assertEquals(0, sectionJump(emptyList(), 3, forward = true))
    }
}

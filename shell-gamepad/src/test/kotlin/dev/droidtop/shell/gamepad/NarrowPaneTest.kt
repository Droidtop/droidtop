package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A narrow pane's compact ladder (docs/SPEC.md 7k, "Narrow panes read the compact ladder"; Droidtop/tracker#739). */
class NarrowPaneTest {
    @Test
    fun `a pane under 600dp is narrow, a whole screen is not`() {
        assertTrue(NarrowPane.isNarrow(400))
        assertTrue(NarrowPane.isNarrow(599))
        assertFalse(NarrowPane.isNarrow(600))
        assertFalse(NarrowPane.isNarrow(1280))
        // A pane not measured yet is not narrow.
        assertFalse(NarrowPane.isNarrow(0))
    }

    @Test
    fun `the compact ladder is one rung down and keeps the pane's title`() {
        val compact = DroidtopTypography.compact()
        assertEquals(15f, compact.titleMedium.fontSize.value)
        assertEquals(15f, compact.bodyMedium.fontSize.value)
        assertEquals(13f, compact.bodySmall.fontSize.value)
        assertEquals(13f, compact.labelSmall.fontSize.value)
        assertEquals(DroidtopTypography.headlineSmall.fontSize, compact.headlineSmall.fontSize)
    }
}

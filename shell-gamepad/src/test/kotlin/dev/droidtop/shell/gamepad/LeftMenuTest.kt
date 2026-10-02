package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/** The left menu's rows and where its cursor starts (docs/SPEC.md 7j, "Gaming controls"). */
class LeftMenuTest {

    private val all = leftMenuEntries(GamingSection.entries)

    @Test
    fun `every destination the mode allows is a row, named as the top bar names it`() {
        assertEquals(GamingSection.entries, all.map { it.section })
        assertEquals(GamingSection.entries.map { it.displayName() }, all.map { it.label })
    }

    @Test
    fun `a mode that hides Settings has no Settings row`() {
        val kiosk = leftMenuEntries(GamingSection.entries.filterNot { it == GamingSection.SETTINGS })
        assertEquals(false, kiosk.any { it.section == GamingSection.SETTINGS })
    }

    @Test
    fun `the cursor opens on the destination the user is on`() {
        GamingSection.entries.forEachIndexed { index, section ->
            assertEquals(index, leftMenuStartIndex(all, section))
        }
    }

    @Test
    fun `a destination the mode hides starts at the top`() {
        val kiosk = leftMenuEntries(GamingSection.entries.filterNot { it == GamingSection.SETTINGS })
        assertEquals(0, leftMenuStartIndex(kiosk, GamingSection.SETTINGS))
    }
}

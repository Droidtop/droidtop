package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion Home's section choices (docs/SPEC.md "The companion's tabs", Droidtop/tracker#328). */
class CompanionHomePrefsTest {
    @Test
    fun `by default every section shows and only Notifications starts folded`() {
        val layout = CompanionHomeLayout()
        CompanionHomeSection.entries.forEach { assertTrue(layout.shows(it)) }
        assertFalse(layout.isOpen(CompanionHomeSection.NOTIFICATIONS))
        assertTrue(layout.isOpen(CompanionHomeSection.NOW))
        assertTrue(layout.isOpen(CompanionHomeSection.CONTINUE))
    }

    @Test
    fun `stored keys round-trip and unknown keys are dropped`() {
        val sections = setOf(CompanionHomeSection.SOCIAL, CompanionHomeSection.WIDGETS)
        assertEquals(sections, CompanionHomePrefs.decode(CompanionHomePrefs.encode(sections)))
        assertEquals(setOf(CompanionHomeSection.NOW), CompanionHomePrefs.decode(setOf("now", "gone_in_a_later_version")))
        assertEquals(emptySet<CompanionHomeSection>(), CompanionHomePrefs.decode(null))
    }

    @Test
    fun `every section has its own settings row id`() {
        val ids = CompanionHomeSection.entries.map(CompanionHomePrefs::itemId)
        assertEquals(ids.size, ids.toSet().size)
    }
}

package dev.droidtop.runtime.systemstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DndCategoriesTest {
    @Test fun `each Android version gets the categories it has`() {
        val oreo = DndCategories.offered(26).map { it.id.removePrefix("pref_dnd_allows_") }
        assertEquals(listOf("reminders", "events", "messages", "calls", "repeat_callers"), oreo)
        val pie = DndCategories.offered(28).map { it.id.removePrefix("pref_dnd_allows_") }
        assertTrue(pie.containsAll(listOf("alarms", "media", "system")))
        assertTrue("conversations" !in pie)
        assertEquals(DndCategories.ALL, DndCategories.offered(34))
    }

    @Test fun `turning one category on or off leaves the others`() {
        assertEquals(32 or 8, DndCategories.with(8, 32, on = true))
        assertEquals(8, DndCategories.with(32 or 8, 32, on = false))
        assertEquals(8, DndCategories.with(8, 32, on = false))
    }

    @Test fun `every category has its own flag and id`() {
        assertEquals(DndCategories.ALL.size, DndCategories.ALL.map { it.flag }.toSet().size)
        assertEquals(DndCategories.ALL.size, DndCategories.ALL.map { it.id }.toSet().size)
    }
}

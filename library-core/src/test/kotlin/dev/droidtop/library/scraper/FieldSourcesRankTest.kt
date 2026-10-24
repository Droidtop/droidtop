package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSourcesRankTest {
    @Test fun `a pick outranks a learned name, which outranks an id or hash, which outranks a name`() {
        val order = listOf("manual", "alias", "id", "name", null).map(FieldSources::rank)
        assertEquals(order.sortedDescending(), order)
        assertEquals(FieldSources.rank("id"), FieldSources.rank("hash"))
        assertEquals(FieldSources.rank(null), FieldSources.rank(PcScraper.NEEDS_PICK))
    }

    @Test fun `only a refresh replaces, and never with a weaker match`() {
        assertFalse("the automatic pass only fills", FieldSources.replaces("id", "name", refreshes = false))
        assertTrue(FieldSources.replaces("name", "name", refreshes = true))
        assertTrue(FieldSources.replaces("id", "name", refreshes = true))
        assertFalse("a name never overwrites a store id's text", FieldSources.replaces("name", "id", refreshes = true))
        assertFalse(FieldSources.replaces("id", "manual", refreshes = true))
        assertTrue("a pick replaces whatever was there", FieldSources.replaces("manual", "manual", refreshes = true))
        assertTrue(FieldSources.replaces("name", null, refreshes = true))
    }

    @Test fun `a fill never lowers what the row's match says`() {
        assertEquals("manual", FieldSources.keptConfidence("name", "manual", replaced = false))
        assertEquals("id", FieldSources.keptConfidence("id", "name", replaced = false))
        assertEquals("name", FieldSources.keptConfidence("name", null, replaced = false))
        assertEquals("manual", FieldSources.keptConfidence("manual", "id", replaced = true))
    }
}

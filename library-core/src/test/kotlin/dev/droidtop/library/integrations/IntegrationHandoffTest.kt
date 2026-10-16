package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A JSON acquire_content integration's hand-off row (docs/SPEC.md 13, "There is no Get games sheet"). */
class IntegrationHandoffTest {
    private val bySystem = "-a android.intent.action.SEARCH -e query {query} -e dir {system.folder}"
    private val queryOnly = "-a android.intent.action.SEARCH -e query {query}"

    @Test
    fun `a template that names the system needs a search scoped to one`() {
        assertFalse(IntegrationHandoff.covers(bySystem, scopedToSystem = false))
        assertTrue(IntegrationHandoff.covers(bySystem, scopedToSystem = true))
        assertTrue(IntegrationHandoff.covers(queryOnly, scopedToSystem = false))
    }

    @Test
    fun `the query is the detail, not the title`() {
        assertEquals("Search Romz" to "for “zelda”", IntegrationHandoff.text("Romz", queryOnly, " zelda "))
        assertEquals("Open Romz" to null, IntegrationHandoff.text("Romz", "-n com.romz/.Main", "zelda"))
    }

    @Test
    fun `its title never matches the query, so it ranks after the sources`() {
        val (title, _) = IntegrationHandoff.text("Romz", queryOnly, "zelda")
        assertEquals(SearchRank.OTHER, SearchRank.score(title, "zelda"))
    }
}

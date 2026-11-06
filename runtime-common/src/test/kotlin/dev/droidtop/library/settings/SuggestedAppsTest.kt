package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The app drawer's Suggested row (Droidtop/tracker#706). */
class SuggestedAppsTest {
    @Test
    fun `with no hours counted the row is the Recent row`() {
        assertEquals(listOf("a", "b", "c"), SuggestedApps.rank(listOf("a", "b", "c"), emptyMap(), 9))
    }

    @Test
    fun `the apps opened at this time of day come first`() {
        var counts = emptyMap<String, Int>()
        repeat(3) { counts = SuggestedApps.recordHour(counts, "news", 8) }
        counts = SuggestedApps.recordHour(counts, "game", 21)
        // At 9 the morning news app (opened around 8) leads; the game opened at night does not count.
        assertEquals(listOf("news", "game", "mail"), SuggestedApps.rank(listOf("game", "mail", "news"), counts, 9))
        assertEquals("news", SuggestedApps.rank(listOf("game", "mail", "news"), counts, 9).first())
        assertEquals("game", SuggestedApps.rank(listOf("mail", "news", "game"), counts, 21).first())
    }

    @Test
    fun `hours wrap around midnight and the count is bounded`() {
        val counts = SuggestedApps.recordHour(emptyMap(), "radio", 23)
        assertEquals("radio", SuggestedApps.rank(listOf("other", "radio"), counts, 0).first())
        var many = emptyMap<String, Int>()
        repeat(10) { many = SuggestedApps.recordHour(many, "app$it", 1, max = 5) }
        assertEquals(5, many.size)
    }
}

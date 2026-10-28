package dev.droidtop.library.integrations

import dev.droidtop.library.scraper.IgdbScraperClient
import dev.droidtop.library.scraper.IgdbSimilar
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pool of games the person does not own (Droidtop/tracker#52): IGDB's answer, the merge and the file. */
class RecommendationPoolTest {
    private fun similar(id: Long, name: String, count: Int = 0) = IgdbSimilar(id, name, null, null, "Platform", null, 80.0, count)

    @Test fun `IGDB's similar_games answer is read by the asked game`() {
        val rows = JSONArray(
            """[{"id":1,"similar_games":[{"id":10,"name":"Celeste","cover":{"url":"//images.igdb.com/t_thumb/a.jpg"},
               "genres":[{"name":"Platform"}],"total_rating":91.5,"total_rating_count":420,
               "involved_companies":[{"company":{"name":"Maddy Makes Games"},"developer":true}]},{"id":0,"name":"No id"}]},
               {"id":2}]""",
        )
        val parsed = IgdbScraperClient.parseSimilar(rows)
        assertEquals(setOf(1L), parsed.keys)
        val celeste = parsed.getValue(1L).single()
        assertEquals("Celeste", celeste.name)
        assertEquals("https://images.igdb.com/t_cover_big/a.jpg", celeste.coverUrl)
        assertEquals("Maddy Makes Games", celeste.developer)
        assertEquals(91.5, celeste.totalRating!!, 0.0)
        assertEquals(420, celeste.ratingCount)
    }

    @Test fun `each game once, never one the library has, most rated first`() {
        val merged = RecommendationPool.merge(
            similarTo = mapOf(1L to listOf(similar(10, "Celeste", 400), similar(11, "Owned Game", 9000)), 2L to listOf(similar(10, "Celeste", 400), similar(12, "Hollow Knight", 900))),
            basis = linkedMapOf(1L to "Super Meat Boy", 2L to "Ori"),
            owned = setOf("ownedgame"),
        )
        assertEquals(listOf("Hollow Knight", "Celeste"), merged.map { it.title })
        assertEquals("Super Meat Boy", merged.single { it.title == "Celeste" }.likeTitle)
        assertEquals(0.8, merged.first().rating!!, 1e-9)
    }

    @Test fun `the file keeps what was built and when, and a broken one reads as none`() {
        val candidates = RecommendationPool.merge(mapOf(1L to listOf(similar(10, "Celeste", 5))), mapOf(1L to "Ori"), emptySet())
        assertEquals(1234L to candidates, RecommendationPool.parse(RecommendationPool.json(1234L, candidates)))
        assertEquals(0L, RecommendationPool.parse("not json").first)
        assertTrue(RecommendationPool.parse(null).second.isEmpty())
    }
}

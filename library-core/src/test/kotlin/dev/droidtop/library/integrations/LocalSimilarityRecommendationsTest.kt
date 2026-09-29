package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking math behind the (currently un-fed, see
 * [LocalSimilarityRecommendations]'s own doc comment) Recommendations
 * API: the Bayesian-weighted rating and the library-overlap similarity
 * score, both pure and both real -- so the moment a real candidate
 * catalog exists, only the fetch/cache layer is new work.
 */
class LocalSimilarityRecommendationsTest {

    @Test
    fun `a title with few votes is pulled toward the catalog average`() {
        // 5 votes of 100 should not beat 5000 votes of 90 -- the owner's
        // own example for why this needs to be Bayesian-weighted at all.
        val fewVotesHighRating = LocalSimilarityRecommendations.weightedRating(
            rating = 100.0, count = 5, priorMean = 70.0, priorCount = 50.0,
        )
        val manyVotesSlightlyLower = LocalSimilarityRecommendations.weightedRating(
            rating = 90.0, count = 5000, priorMean = 70.0, priorCount = 50.0,
        )
        assertTrue(
            "few high votes ($fewVotesHighRating) should not outrank many solid votes ($manyVotesSlightlyLower)",
            manyVotesSlightlyLower > fewVotesHighRating,
        )
    }

    @Test
    fun `zero votes returns exactly the prior mean`() {
        assertEquals(70.0, LocalSimilarityRecommendations.weightedRating(rating = 100.0, count = 0, priorMean = 70.0, priorCount = 50.0), 0.0001)
    }

    @Test
    fun `a huge vote count converges on the title's own rating`() {
        val result = LocalSimilarityRecommendations.weightedRating(rating = 95.0, count = 1_000_000, priorMean = 70.0, priorCount = 50.0)
        assertTrue("expected close to 95.0, got $result", result > 94.9)
    }

    @Test
    fun `genre and developer overlap with a heavily-played owned game scores higher than no overlap`() {
        val ownedWeights = listOf(
            Triple("Platformer", "Nintendo", 10.0),
            Triple("Puzzle", "Some Studio", 1.0),
        )
        val matching = LocalSimilarityRecommendations.librarySimilarity("Platformer", "Nintendo", ownedWeights)
        val nonMatching = LocalSimilarityRecommendations.librarySimilarity("Racing", "Other Studio", ownedWeights)
        assertTrue("expected matching ($matching) > non-matching ($nonMatching)", matching > nonMatching)
        assertEquals(0.0, nonMatching, 0.0001)
    }

    @Test
    fun `similarity score stays within 0 to 1 regardless of library size`() {
        val bigLibrary = (1..200).map { Triple("Platformer", "Nintendo", 5.0) }
        val score = LocalSimilarityRecommendations.librarySimilarity("Platformer", "Nintendo", bigLibrary)
        assertTrue("score $score should be <= 1.0", score <= 1.0)
    }

    @Test
    fun `similarity with an empty library is zero, not a division error`() {
        assertEquals(0.0, LocalSimilarityRecommendations.librarySimilarity("Platformer", "Nintendo", emptyList()), 0.0001)
    }

    @Test
    fun `a recently played, heavily played game weighs more than an old, barely played one`() {
        val recentHeavy = LocalSimilarityRecommendations.playWeight(playtimeSeconds = 100 * 3600L, daysSinceLastPlayed = 2)
        val oldLight = LocalSimilarityRecommendations.playWeight(playtimeSeconds = 300L, daysSinceLastPlayed = 400)
        assertTrue("expected recent/heavy ($recentHeavy) > old/light ($oldLight)", recentHeavy > oldLight)
    }

    @Test
    fun `an unplayed owned game still weighs at least a floor amount, never zero`() {
        val weight = LocalSimilarityRecommendations.playWeight(playtimeSeconds = 0L, daysSinceLastPlayed = null)
        assertTrue("expected a positive floor weight, got $weight", weight > 0.0)
    }

    private fun game(id: String, genre: String?, developer: String? = null, playSeconds: Long = 0, rating: Float? = null, systemId: String? = null, hidden: Boolean = false) =
        dev.droidtop.library.LibraryEntry(
            id = id, title = id, kind = dev.droidtop.library.LibraryEntryKind.CONSOLE_ROM,
            playtimeSeconds = playSeconds, playCount = if (playSeconds > 0) 1 else 0,
            genre = genre, developer = developer, rating = rating, systemId = systemId, hidden = hidden,
        )

    @Test
    fun `rank suggests only unplayed games that resemble what was played, best first`() {
        val library = listOf(
            game("Played Platformer", "Platformer", "Studio A", playSeconds = 36_000),
            game("Unplayed Platformer", "Platformer"),
            game("Unplayed Racer", "Racing"),
            game("Same Studio", null, "Studio A", rating = 0.2f),
            game("Hidden Platformer", "Platformer", hidden = true),
        )
        val picks = LocalSimilarityRecommendations.rank(library, RecommendationScope.Overall, limit = 10, nowMs = 0L)
        assertEquals(listOf("Unplayed Platformer", "Same Studio"), picks.map { it.title })
        assertEquals("Same genre as Played Platformer", picks.first().reason)
    }

    @Test
    fun `rank recommends nothing without play history and never invents titles`() {
        val library = listOf(game("A", "Platformer"), game("B", "Platformer"))
        assertTrue(LocalSimilarityRecommendations.rank(library, RecommendationScope.Overall, 10, 0L).isEmpty())
        assertTrue(LocalSimilarityRecommendations.rank(emptyList(), RecommendationScope.Overall, 10, 0L).isEmpty())
    }

    @Test
    fun `rank narrows to a platform and to one named game`() {
        val library = listOf(
            game("Played", "Platformer", playSeconds = 3600),
            game("On NES", "Platformer", systemId = "nes"),
            game("On SNES", "Platformer", systemId = "snes"),
        )
        assertEquals(listOf("On NES"), LocalSimilarityRecommendations.rank(library, RecommendationScope.Platform("nes"), 10, 0L).map { it.title })
        val because = LocalSimilarityRecommendations.rank(library, RecommendationScope.BecauseOf("played"), 10, 0L)
        assertEquals(setOf("On NES", "On SNES"), because.map { it.title }.toSet())
        assertEquals("Because you played played", because.first().reason)
    }

    @Test
    fun `rank honours the limit`() {
        val library = listOf(game("Played", "Platformer", playSeconds = 3600)) + (1..5).map { game("G$it", "Platformer") }
        assertEquals(2, LocalSimilarityRecommendations.rank(library, RecommendationScope.Overall, 2, 0L).size)
    }
}

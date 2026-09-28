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
}

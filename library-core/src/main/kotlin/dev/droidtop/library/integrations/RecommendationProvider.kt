package dev.droidtop.library.integrations

import android.content.Context
import kotlin.math.ln

/**
 * The Recommendations API (docs/SPEC.md 12a "Recommendations API"):
 * droidtop's own "games you might like" feature -- never plugin-fed,
 * never plugin-named (owner directive: "droidtop gets its own,
 * plugin-independent Recommendations feature... it never mentions or
 * depends on any plugin"). Shaped as an interface, same "think in APIs"
 * principle as [GameSourceProvider], so a future re-ranking provider (a
 * server-side model, a second local heuristic) can be added or swapped
 * without a UI change -- droidtop's UI only ever calls [recommend].
 */
interface RecommendationProvider {
    /** Ranked candidates for [scope], best first, at most [limit]. Off the main thread; never throws -- a provider that can't answer (no data yet, a cache miss mid-rebuild) returns an empty list, the same "recommend nothing rather than something wrong" contract every candidate here already follows. */
    suspend fun recommend(context: Context, scope: RecommendationScope, limit: Int): List<Recommendation>
}

/** What [RecommendationProvider.recommend] is being asked for -- overall, narrowed to one platform/system, or "more like this owned game" (the "Because you played X" view). */
sealed interface RecommendationScope {
    data object Overall : RecommendationScope
    data class Platform(val systemId: String) : RecommendationScope
    data class BecauseOf(val ownedTitle: String) : RecommendationScope
}

/**
 * One ranked recommendation. [title]/[platform] are what
 * [GetMoreComposer.composeEmpty] hands to every [GameSourceProvider.lookup]
 * to fill in "where to get it" -- a recommendation with no reachable
 * source (true for most ROM-era titles: SPEC 12a "most ROMs have no
 * official source") still has a real [reason] and [score] and is shown
 * with an empty source list, never dropped for having no source.
 */
data class Recommendation(
    val title: String,
    val platform: String?,
    val reason: String,
    val artUrl: String? = null,
    val description: String? = null,
    val score: Double = 0.0,
)

/**
 * The built-in [RecommendationProvider]. Owner's specified signal set is
 * IGDB `total_rating`/`total_rating_count` (Bayesian-weighted),
 * `similar_games`, genre/theme/developer overlap with the user's library
 * weighted by play history, ScreenScraper ratings, and a RetroAchievements
 * popularity proxy -- **all of which need droidtop's own scraped catalog
 * of games-not-owned, which does not exist yet** (docs/SPEC.md's own
 * standing gap: "droidtop has no media-scraper of its own"). Without a
 * scraper there is no candidate pool of un-owned titles to rank at all,
 * so [recommend] below is a real, wired implementation of the API and
 * its ranking math ([weightedRating], [librarySimilarity] are both real
 * and unit-tested), honestly returning an empty candidate list rather
 * than a fabricated one until that catalog exists -- tracked as its own
 * item, not folded silently into this change (see the "Recommendations"
 * issue this file's SPEC section references).
 *
 * [librarySimilarity] and [weightedRating] are kept here, pure and
 * public for testing, so the moment a real candidate catalog exists the
 * only new code needed is the fetch/cache layer -- the ranking math
 * this class will use is already correct and already tested.
 */
class LocalSimilarityRecommendations : RecommendationProvider {
    override suspend fun recommend(context: Context, scope: RecommendationScope, limit: Int): List<Recommendation> {
        // No un-owned candidate catalog yet -- see the class doc. Returning
        // real library entries here would violate "never recommend a game
        // the user already owns"; returning invented titles would violate
        // "no AI-generated/fabricated content". Empty is the only honest
        // answer until a scraped catalog exists.
        return emptyList()
    }

    companion object {
        /**
         * IMDB/Bayesian-weighted rating: a title with few votes is pulled
         * toward [priorMean] (the whole catalog's average) rather than
         * trusting its own small sample, exactly so "5 votes of 100"
         * cannot outrank "5,000 votes of 90" (owner's own example). Pure,
         * unit-tested (WeightedRatingTest). Standard IMDB formula:
         * `(count/(count+priorCount))*rating + (priorCount/(count+priorCount))*priorMean`.
         */
        fun weightedRating(rating: Double, count: Int, priorMean: Double, priorCount: Double): Double {
            if (count <= 0) return priorMean
            val c = count.toDouble()
            return (c / (c + priorCount)) * rating + (priorCount / (c + priorCount)) * priorMean
        }

        /**
         * A candidate's similarity to the user's own library, 0..1: genre
         * and developer overlap with an owned game, weighted by how much
         * that owned game was played -- recently played and long-played
         * games count more ([recencyWeight]/[playtimeWeight] fold both
         * into one per-owned-game weight the caller supplies, so this
         * function itself stays pure and Android/Context-free). Pure,
         * unit-tested (LibrarySimilarityTest).
         *
         * [ownedWeights] pairs one owned game's (genre, developer) with
         * its own weight (already combining recency+playtime); the result
         * is the weighted fraction of genre/developer signals that match,
         * normalized so a library of one game and a library of a hundred
         * both produce a comparable 0..1 score rather than one that grows
         * with library size.
         */
        fun librarySimilarity(
            candidateGenre: String?,
            candidateDeveloper: String?,
            ownedWeights: List<Triple<String?, String?, Double>>,
        ): Double {
            if (ownedWeights.isEmpty()) return 0.0
            var matchedWeight = 0.0
            var totalWeight = 0.0
            ownedWeights.forEach { (genre, developer, weight) ->
                totalWeight += weight
                val genreMatch = candidateGenre != null && genre != null && candidateGenre.equals(genre, ignoreCase = true)
                val devMatch = candidateDeveloper != null && developer != null && candidateDeveloper.equals(developer, ignoreCase = true)
                if (genreMatch || devMatch) {
                    val strength = (if (genreMatch) 0.6 else 0.0) + (if (devMatch) 0.4 else 0.0)
                    matchedWeight += weight * strength
                }
            }
            if (totalWeight <= 0.0) return 0.0
            return (matchedWeight / totalWeight).coerceIn(0.0, 1.0)
        }

        /** How much one owned game weighs toward similarity: recently played and long-played games count more, but neither factor alone can zero out a game that's merely old or merely short -- log-scaled playtime so an hour and a hundred hours don't differ 100x, floor of 1.0 so an unplayed-but-owned game still counts a little. Pure, unit-tested. */
        fun playWeight(playtimeSeconds: Long, daysSinceLastPlayed: Long?): Double {
            val playtimeHours = playtimeSeconds / 3600.0
            val playtimeFactor = 1.0 + ln(1.0 + playtimeHours)
            val recencyFactor = when {
                daysSinceLastPlayed == null -> 1.0
                daysSinceLastPlayed <= 7 -> 2.0
                daysSinceLastPlayed <= 30 -> 1.5
                daysSinceLastPlayed <= 90 -> 1.2
                else -> 1.0
            }
            return playtimeFactor * recencyFactor
        }
    }
}

/**
 * The UI composition rule (SPEC 12a "Composition"), owner directive:
 * "search results = library + Sources.search; 'Get more' with no query =
 * Recommendations.recommend ∩ Sources.lookup; 'Where to get it' =
 * Sources.lookup for one game." This object is that composition, kept
 * separate from both APIs so neither one needs to know about the other.
 */
object GetMoreComposer {
    /** "Get more" with a real query: [PluginSearchAggregator.searchAll] over every [sources] -- unchanged from before Recommendations existed. */
    suspend fun composeSearch(
        context: Context,
        sources: List<GameSourceProvider>,
        query: String,
        platform: String?,
    ): List<SourceHit> = PluginSearchAggregator.searchAll(context, sources, query, platform)

    /**
     * "Get more" with no query: droidtop's own [recommendations] for
     * [scope], each one asked of every [sources] via [GameSourceProvider.lookup]
     * (title+platform, the same fan-out/timeout shape [composeSearch]
     * already uses per source). A recommendation with no matching source
     * is KEPT with an empty [RecommendedRow.hits] list, never dropped --
     * "most ROMs have no official source... show the recommendation
     * anyway, and simply list no source" (owner directive).
     */
    suspend fun composeEmpty(
        context: Context,
        sources: List<GameSourceProvider>,
        recommendations: RecommendationProvider,
        scope: RecommendationScope,
        limit: Int,
    ): List<RecommendedRow> {
        val picks = recommendations.recommend(context, scope, limit)
        return picks.map { pick ->
            val hits = fanOutLookup(context, sources, pick)
            RecommendedRow(pick, hits)
        }
    }

    private suspend fun fanOutLookup(context: Context, sources: List<GameSourceProvider>, pick: Recommendation): List<SourceHit> =
        PluginSearchAggregator.fanOut(sources) { source ->
            source.lookup(context, pick.title, pick.platform).getOrNull().orEmpty()
        }
}

/** One recommended game plus whatever sources answered "yes" for it -- [hits] is empty for a title no source can supply, which is expected and shown, not hidden. */
data class RecommendedRow(val recommendation: Recommendation, val hits: List<SourceHit>)

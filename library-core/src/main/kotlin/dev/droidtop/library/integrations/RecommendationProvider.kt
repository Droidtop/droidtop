package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.LibraryEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * The built-in [RecommendationProvider], fed by what droidtop really has:
 * the library's own scraped facts (genre, developer, rating) and the play
 * history (play count, playtime, last played). It recommends the games in
 * the user's library they have NOT played yet that most resemble what they
 * do play, weighted by how long and how recently they played it -- "Because
 * you played X". It never recommends a played game and never invents a
 * title.
 *
 * The owner's fuller signal set (IGDB `total_rating` and `similar_games`,
 * ScreenScraper ratings, a RetroAchievements popularity proxy) needs a
 * catalog of games the user does NOT own, which droidtop does not fetch
 * yet. When one exists it becomes a second candidate pool in [rank]; the
 * ranking math ([weightedRating], [SimilarityIndex]) is already the shape it
 * needs. Until then the pool is the user's own unplayed games, which are
 * already owned, so a [Recommendation] has no "where to get it" to add.
 *
 * [library] supplies the entries and is called off the main thread; the
 * default supplies none, which answers nothing.
 */
class LocalSimilarityRecommendations(
    private val library: suspend () -> List<LibraryEntry> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) : RecommendationProvider {
    override suspend fun recommend(context: Context, scope: RecommendationScope, limit: Int): List<Recommendation> =
        try {
            withContext(Dispatchers.Default) { rank(library(), scope, limit, now()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        /** Extra score for a well-rated game (rating is 0..1); small next to similarity, which must be above zero for a game to be recommended at all. */
        private const val RATING_BONUS = 0.25
        private const val DAY_MS = 86_400_000L

        private fun LibraryEntry.isPlayed(): Boolean = playCount > 0 || playtimeSeconds > 0 || lastPlayedEpochMs != null

        private fun LibraryEntry.signal(nowMs: Long) = OwnedSignal(
            title = title,
            genre = genre,
            developer = developer,
            weight = playWeight(playtimeSeconds, lastPlayedEpochMs?.let { ((nowMs - it) / DAY_MS).coerceAtLeast(0) }),
        )

        /**
         * The ranking over [entries], pure and linear in the library: the
         * played games (or, for [RecommendationScope.BecauseOf], the one
         * named game) are folded into a [SimilarityIndex] once, then every
         * unplayed, visible candidate is scored by lookup. Best first, at
         * most [limit]; a candidate that resembles nothing played is left out.
         */
        fun rank(entries: List<LibraryEntry>, scope: RecommendationScope, limit: Int, nowMs: Long): List<Recommendation> {
            if (limit <= 0) return emptyList()
            val visible = entries.filter { !it.hidden && !it.broken }
            val basis = when (scope) {
                is RecommendationScope.BecauseOf -> visible.filter { it.title.equals(scope.ownedTitle, ignoreCase = true) }
                else -> visible.filter { it.isPlayed() }
            }
            if (basis.isEmpty()) return emptyList()
            val index = SimilarityIndex(basis.map { it.signal(nowMs) })
            val basisIds = basis.mapTo(HashSet()) { it.id }
            return visible.asSequence()
                .filter { it.id !in basisIds && !it.isPlayed() }
                .filter { scope !is RecommendationScope.Platform || it.systemId == scope.systemId }
                .mapNotNull { candidate ->
                    val similarity = index.score(candidate.genre, candidate.developer)
                    if (similarity <= 0.0) return@mapNotNull null
                    Recommendation(
                        title = candidate.title,
                        platform = candidate.systemId,
                        reason = index.reason(candidate.genre, candidate.developer, scope),
                        artUrl = candidate.artworkUri,
                        description = candidate.description,
                        score = similarity + RATING_BONUS * (candidate.rating?.toDouble() ?: 0.0).coerceIn(0.0, 1.0),
                    )
                }
                .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.title.lowercase() })
                .take(limit)
                .toList()
        }

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
        ): Double = SimilarityIndex(ownedWeights.map { (genre, developer, weight) -> OwnedSignal("", genre, developer, weight) })
            .score(candidateGenre, candidateDeveloper)

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

/** One played game as the similarity index sees it: what it is and how much it counts ([LocalSimilarityRecommendations.playWeight]). */
internal data class OwnedSignal(val title: String, val genre: String?, val developer: String?, val weight: Double)

/**
 * The library's genre and developer signal, folded once so scoring a
 * candidate is two map lookups however large the library is. A genre match
 * is worth 0.6 and a developer match 0.4 of an owned game's weight; the
 * score is that matched weight over the total weight, so it stays 0..1 and
 * a library of one game and of a hundred compare alike.
 */
internal class SimilarityIndex(owned: List<OwnedSignal>) {
    private val total = owned.sumOf { it.weight }
    private val byGenre = HashMap<String, Double>()
    private val byDeveloper = HashMap<String, Double>()
    private val topGenre = HashMap<String, OwnedSignal>()
    private val topDeveloper = HashMap<String, OwnedSignal>()

    init {
        owned.forEach { game ->
            game.genre?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { key ->
                byGenre.merge(key, game.weight, Double::plus)
                topGenre.merge(key, game) { a, b -> if (b.weight > a.weight) b else a }
            }
            game.developer?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { key ->
                byDeveloper.merge(key, game.weight, Double::plus)
                topDeveloper.merge(key, game) { a, b -> if (b.weight > a.weight) b else a }
            }
        }
    }

    fun score(genre: String?, developer: String?): Double {
        if (total <= 0.0) return 0.0
        val matched = 0.6 * (genre?.trim()?.lowercase()?.let { byGenre[it] } ?: 0.0) +
            0.4 * (developer?.trim()?.lowercase()?.let { byDeveloper[it] } ?: 0.0)
        return (matched / total).coerceIn(0.0, 1.0)
    }

    /** Why a candidate scored: the played game it most resembles, said plainly. */
    fun reason(genre: String?, developer: String?, scope: RecommendationScope): String {
        if (scope is RecommendationScope.BecauseOf) return "Because you played ${scope.ownedTitle}"
        val byGenreGame = genre?.trim()?.lowercase()?.let { topGenre[it] }
        if (byGenreGame != null) return "Same genre as ${byGenreGame.title}"
        val byDevGame = developer?.trim()?.lowercase()?.let { topDeveloper[it] }
        return if (byDevGame != null) "Same developer as ${byDevGame.title}" else "Like games you play"
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
    /** "Get more" with a real query: [PluginSearchAggregator.searchAll] over every [sources], one outcome per source. */
    suspend fun composeSearch(
        context: Context,
        sources: List<GameSourceProvider>,
        query: String,
        platform: String?,
    ): List<SourceOutcome> = PluginSearchAggregator.searchAll(context, sources, query, platform)

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

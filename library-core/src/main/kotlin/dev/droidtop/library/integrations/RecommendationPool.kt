package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.IgdbScraperClient
import dev.droidtop.library.scraper.IgdbSimilar
import dev.droidtop.library.scraper.PcFlavour
import dev.droidtop.library.scraper.PcMatching
import dev.droidtop.library.scraper.ScrapeLookup
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The games the person does not own that Recommendations can suggest (docs/SPEC.md 12a "Recommendations API",
 * Droidtop/tracker#52): IGDB's `similar_games` of the games they play most, with each one's genre, developer and
 * IGDB `total_rating` and how many ratings it rests on. It needs the person's own IGDB credentials (the PC source's);
 * without them the pool stays empty and Recommendations offers the library's unplayed games only, as before.
 *
 * Kept in droidtop's files as JSON and rebuilt in the background when it is a week old ([refreshIfStale]); nothing
 * on screen waits for IGDB. A candidate the library already has, by its normalized name, is never offered.
 */
object RecommendationPool {
    /** One game IGDB found [likeTitle] (a played game) to be similar to. [rating] is 0..1, null when unrated. */
    data class Candidate(
        val title: String,
        val igdbId: Long,
        val likeTitle: String,
        val artUrl: String?,
        val description: String?,
        val genre: String?,
        val developer: String?,
        val rating: Double?,
        val ratingCount: Int,
    )

    /** How many played games, most played first, the pool is built from: one IGDB search each, then one query per ten. */
    const val BASIS = 20

    /** The most candidates kept. */
    const val KEPT = 300

    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private fun file(context: Context) = File(context.applicationContext.filesDir, "recommendation-pool.json")

    /** The pool as last built, and when; empty when there is none. Disk. */
    fun read(context: Context): Pair<Long, List<Candidate>> = parse(runCatching { file(context).readText() }.getOrNull())

    internal fun parse(text: String?): Pair<Long, List<Candidate>> = runCatching {
        val json = JSONObject(requireNotNull(text))
        val array = json.optJSONArray("candidates") ?: JSONArray()
        json.optLong("builtAt", 0L) to (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Candidate(
                title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                igdbId = o.optLong("igdb", 0L).takeIf { it > 0 } ?: return@mapNotNull null,
                likeTitle = o.optString("like"),
                artUrl = o.optString("art").takeIf { it.isNotBlank() },
                description = o.optString("description").takeIf { it.isNotBlank() },
                genre = o.optString("genre").takeIf { it.isNotBlank() },
                developer = o.optString("developer").takeIf { it.isNotBlank() },
                rating = if (o.has("rating")) o.optDouble("rating") else null,
                ratingCount = o.optInt("count", 0),
            )
        }
    }.getOrDefault(0L to emptyList())

    internal fun json(builtAt: Long, candidates: List<Candidate>): String = JSONObject()
        .put("builtAt", builtAt)
        .put("candidates", JSONArray(candidates.map { c ->
            JSONObject().put("title", c.title).put("igdb", c.igdbId).put("like", c.likeTitle)
                .putOpt("art", c.artUrl).putOpt("description", c.description).putOpt("genre", c.genre)
                .putOpt("developer", c.developer).putOpt("rating", c.rating).put("count", c.ratingCount)
        }))
        .toString()

    /**
     * The candidates IGDB's answers give, by [similarTo] (asked id to its similar games) and [basis] (asked id to the
     * played game's title): each game once, under the played game it was found for first; none the library [owned]
     * already has by normalized name; the most rated first, at most [KEPT]. Pure.
     */
    internal fun merge(similarTo: Map<Long, List<IgdbSimilar>>, basis: Map<Long, String>, owned: Set<String>): List<Candidate> {
        val seen = HashSet<Long>()
        val taken = HashSet<String>()
        return basis.keys.flatMap { asked ->
            similarTo[asked].orEmpty().mapNotNull { game ->
                val key = PcMatching.normalize(game.name)
                if (key.isEmpty() || key in owned || !seen.add(game.id) || !taken.add(key)) return@mapNotNull null
                Candidate(
                    title = game.name,
                    igdbId = game.id,
                    likeTitle = basis.getValue(asked),
                    artUrl = game.coverUrl,
                    description = game.description,
                    genre = game.genre,
                    developer = game.developer,
                    rating = game.totalRating?.let { (it / 100.0).coerceIn(0.0, 1.0) },
                    ratingCount = game.ratingCount,
                )
            }
        }.sortedByDescending { it.ratingCount }.take(KEPT)
    }

    private val building = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Rebuilds the pool in the background when it is missing or a week old and IGDB is set up; returns at once. A
     * rebuild already running is not started twice.
     */
    fun refreshIfStale(context: Context, played: List<LibraryEntry>, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        if (PcFlavour.igdbCredentials(app) == null || played.isEmpty()) return
        val (builtAt, _) = read(app)
        if (now - builtAt < MAX_AGE_MS) return
        if (!building.compareAndSet(false, true)) return
        scope.launch {
            try {
                rebuild(app, played, now)
            } finally {
                building.set(false)
            }
        }
    }

    /**
     * Builds the pool from [played], most played first: each game's IGDB id by an exact-name search, then the games
     * IGDB lists as similar, ten games a query. A refusal (a key, a limit) keeps the pool as it was. Network; blocking.
     */
    internal fun rebuild(context: Context, played: List<LibraryEntry>, now: Long) {
        val (id, secret) = PcFlavour.igdbCredentials(context) ?: return
        val owned = played.mapTo(HashSet()) { PcMatching.normalize(it.title) }
        val basis = LinkedHashMap<Long, String>()
        for (entry in played.take(BASIS)) {
            val key = PcMatching.normalize(entry.title)
            when (val found = IgdbScraperClient.search(id, secret, entry.title, limit = 5)) {
                is ScrapeLookup.Found -> found.value.firstOrNull { PcMatching.normalize(it.name) == key }?.id?.let { basis.putIfAbsent(it, entry.title) }
                is ScrapeLookup.Refused -> return
                ScrapeLookup.NoMatch -> Unit
            }
        }
        if (basis.isEmpty()) return
        val similar = HashMap<Long, List<IgdbSimilar>>()
        for (chunk in basis.keys.chunked(IgdbScraperClient.MAX_SIMILAR_IDS)) {
            when (val found = IgdbScraperClient.similar(id, secret, chunk)) {
                is ScrapeLookup.Found -> similar.putAll(found.value)
                is ScrapeLookup.Refused -> return
                ScrapeLookup.NoMatch -> Unit
            }
        }
        write(file(context), json(now, merge(similar, basis, owned)))
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

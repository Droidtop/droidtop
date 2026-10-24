package dev.droidtop.library.identity

import android.content.Context
import dev.droidtop.library.GameTitleParser
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.MatchQueueEntity
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.scraper.PcGameIds
import dev.droidtop.library.scraper.PcMatch
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The games waiting for the person's pick, with what the source answered (Droidtop/tracker#318 §4.1, §5): a scrape that
 * does not take a match queues the game with its best five candidates, so Review Matches opens at once and works
 * offline, and a match (automatic or picked) takes it off. Today's matcher still decides what is taken (the new score
 * is not live until its calibration set passes, §3.3); the score only sorts the queue into Likely, Unsure and Not
 * found. A game the person marks Local only stays out of the queue and is never asked about again until they undo it.
 */
object MatchQueue {
    const val QUEUED = "queued"
    const val LOCAL_ONLY = "local_only"

    /** The most candidates kept for a card (§5.1). */
    const val KEPT = 5

    /** The queue's tabs, by the effort each takes (§5.1). */
    enum class Tab { LIKELY, UNSURE, NOT_FOUND }

    /** One candidate on a card: the match to apply, and its 0 to 100 score. */
    data class Choice(val match: PcMatch, val score: Int)

    /** One game in the queue. */
    data class Item(
        val gameId: String,
        val tab: Tab,
        val source: String,
        val searched: String,
        val choices: List<Choice>,
        val askedAt: Long,
    )

    /** Where [verdict]'s game belongs: a tier taken or likely is Likely, unsure is Unsure, none is Not found. Pure. */
    fun tabOf(tier: MatchScore.Tier): Tab = when (tier) {
        MatchScore.Tier.A, MatchScore.Tier.A_ALIAS, MatchScore.Tier.B, MatchScore.Tier.C -> Tab.LIKELY
        MatchScore.Tier.D -> Tab.UNSURE
        MatchScore.Tier.E -> Tab.NOT_FOUND
    }

    /** [matches] for the game named [local], scored and kept best first, with the tab they put it in. Pure. */
    fun rank(local: String, matches: List<PcMatch>): Pair<Tab, List<Choice>> {
        val candidates = matches.mapIndexed { i, m -> MatchScore.Candidate(i.toString(), m.name, year = m.year) }
        val verdict = MatchScore.verdict(MatchScore.Local(GameTitleParser.parseName(local).title, local), candidates)
        val choices = verdict.ranked.take(KEPT).map { Choice(matches[it.candidate.id.toInt()], it.score) }
        return tabOf(verdict.tier) to choices
    }

    /** Queues [entry], whose search for [searched] in [source] gave [matches] and none was taken. A Local only game stays out. */
    suspend fun put(context: Context, entry: LibraryEntry, source: String, searched: String, matches: List<PcMatch>, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val dao = RomDatabase.get(context).romDao()
            if (dao.matchQueueRow(entry.id)?.state == LOCAL_ONLY) return@withContext
            val local = File(entry.id).takeIf { it.isDirectory }?.name ?: entry.title
            val (tab, choices) = rank(local, matches)
            dao.putMatchQueueRow(MatchQueueEntity(entry.id, QUEUED, tab.name, source, searched, choicesJson(choices), now))
        }

    /** Takes [gameId] off the queue: it was matched. A Local only mark stays. */
    suspend fun clear(context: Context, gameId: String) = withContext(Dispatchers.IO) {
        RomDatabase.get(context).romDao().deleteQueuedMatch(gameId, QUEUED)
    }

    /** Marks [gameId] Local only ([on]): out of the queue and never asked about, or back to being asked. */
    suspend fun setLocalOnly(context: Context, gameId: String, on: Boolean, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val dao = RomDatabase.get(context).romDao()
        if (on) {
            val row = dao.matchQueueRow(gameId)
            dao.putMatchQueueRow((row ?: MatchQueueEntity(gameId, LOCAL_ONLY, Tab.NOT_FOUND.name, "", "", "[]", now)).copy(state = LOCAL_ONLY))
        } else {
            dao.deleteQueuedMatch(gameId, LOCAL_ONLY)
        }
    }

    suspend fun isLocalOnly(context: Context, gameId: String): Boolean = withContext(Dispatchers.IO) {
        RomDatabase.get(context).romDao().matchQueueRow(gameId)?.state == LOCAL_ONLY
    }

    /** Every queued game, by tab then name. */
    suspend fun items(context: Context): List<Item> = withContext(Dispatchers.IO) {
        RomDatabase.get(context).romDao().matchQueueRows(QUEUED).mapNotNull(::itemOf)
    }

    /** How many games wait in each tab. */
    suspend fun counts(context: Context): Map<Tab, Int> = items(context).groupingBy { it.tab }.eachCount()

    internal fun itemOf(row: MatchQueueEntity): Item? {
        val tab = runCatching { Tab.valueOf(row.tab) }.getOrNull() ?: return null
        return Item(row.gameId, tab, row.source, row.searched, choicesFrom(row.candidates), row.askedAt)
    }

    internal fun choicesJson(choices: List<Choice>): String = JSONArray(choices.map { JSONObject().put("score", it.score).put("match", matchJson(it.match)) }).toString()

    internal fun choicesFrom(text: String): List<Choice> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Choice(matchFrom(o.getJSONObject("match")), o.optInt("score"))
        }
    }.getOrDefault(emptyList())

    /** What [PcScraper.apply][dev.droidtop.library.scraper.PcScraper.apply] needs of a match: the rest is asked by its ids. */
    internal fun matchJson(m: PcMatch): JSONObject = JSONObject()
        .put("name", m.name).put("source", m.sourceLabel)
        .putOpt("year", m.year).putOpt("cover", m.coverUrl).putOpt("description", m.description)
        .putOpt("developer", m.developer).putOpt("publisher", m.publisher).putOpt("genre", m.genre)
        .putOpt("release_date", m.releaseDate).putOpt("rating", m.rating?.toDouble()).putOpt("series", m.series)
        .putOpt("steam", m.ids.steamAppId).putOpt("gog", m.ids.gogId).putOpt("igdb", m.ids.igdbId)
        .putOpt("sgdb", m.ids.steamGridDbId).putOpt("lutris", m.ids.lutrisSlug)

    internal fun matchFrom(o: JSONObject): PcMatch = PcMatch(
        name = o.getString("name"),
        sourceLabel = o.optString("source"),
        year = o.optIntOrNull("year"),
        coverUrl = o.optStringOrNull("cover"),
        description = o.optStringOrNull("description"),
        developer = o.optStringOrNull("developer"),
        publisher = o.optStringOrNull("publisher"),
        genre = o.optStringOrNull("genre"),
        releaseDate = o.optStringOrNull("release_date"),
        rating = if (o.has("rating")) o.getDouble("rating").toFloat() else null,
        series = o.optStringOrNull("series"),
        ids = PcGameIds(
            steamAppId = o.optIntOrNull("steam"),
            gogId = o.optStringOrNull("gog"),
            igdbId = if (o.has("igdb")) o.getLong("igdb") else null,
            steamGridDbId = o.optIntOrNull("sgdb"),
            lutrisSlug = o.optStringOrNull("lutris"),
        ),
    )

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
}

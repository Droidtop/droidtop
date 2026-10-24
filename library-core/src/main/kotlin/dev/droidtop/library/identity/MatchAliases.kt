package dev.droidtop.library.identity

import android.content.Context
import dev.droidtop.library.consoles.MatchAliasEntity
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.scraper.PcMatch
import dev.droidtop.library.scraper.PcMatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the person's picks teach (Droidtop/tracker#318 §6.4): a game matched by hand records its searched name,
 * compacted as [PcMatching.normalize] does, with the match picked. A later pass that searches the same name, for
 * another folder or a new version of the game, takes that match at tier A- with no search. A new pick for the same
 * name replaces the old one. Automatic matches teach nothing.
 */
object MatchAliases {
    /** The key [searched] is learned and looked up under; empty when the name has nothing to compare. Pure. */
    fun keyOf(searched: String): String = PcMatching.normalize(searched)

    /** Learns that [searched] means [match]: the person picked it. */
    suspend fun learn(context: Context, searched: String, match: PcMatch, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val key = keyOf(searched).takeIf { it.isNotEmpty() } ?: return@withContext
        RomDatabase.get(context).romDao().putMatchAlias(MatchAliasEntity(key, MatchQueue.matchJson(match).toString(), now))
    }

    /** The match the person picked for [searched] before, or null. */
    suspend fun find(context: Context, searched: String): PcMatch? = withContext(Dispatchers.IO) {
        val key = keyOf(searched).takeIf { it.isNotEmpty() } ?: return@withContext null
        val row = RomDatabase.get(context).romDao().matchAlias(key) ?: return@withContext null
        runCatching { MatchQueue.matchFrom(JSONObject(row.match)) }.getOrNull()
    }
}

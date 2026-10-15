package dev.droidtop.library.saves

import android.content.Context
import dev.droidtop.library.computers.ComputerSaves
import dev.droidtop.net.peer.Computers
import org.json.JSONObject
import java.io.File

/**
 * What identifies one game, from strongest to weakest (docs/SPEC.md 7o "Saves", owner 2026-10-14: "We also need to be
 * able to index against the other games we detect, especially with metadata scrapers"): the store ids the entry
 * carries, the ids a scraper learned for it ([GameIdKind], kept in `game_ids`), the install folder's name and the
 * exact title key. Never a fuzzy name.
 */
data class SaveIdentity(
    val steamId: String?,
    val gogId: String?,
    /** `game_ids` as the scrapers wrote them. */
    val scraped: Map<String, String>,
    val installFolder: String?,
    /** The exact key two spellings of one title share ([dev.droidtop.library.StoreIdentity.titleKey]), or null when nothing is left of it. */
    val titleKey: String?,
)

/** A place that knows which save rule belongs to which identity: today the specs paired computers answered with. */
interface SaveIndex {
    /** The name of the rule [kind] (one of [SaveMatchHow] or a [GameIdKind]) [value] identifies, or null. */
    fun find(kind: String, value: String): String?
}

/** The rule a game was matched to and the rung it matched on. */
data class SaveMatch(val name: String, val how: String) {
    /** The line saying so, in the words a person reads. */
    fun words(): String = when (how) {
        SaveMatchHow.STEAM -> "matched by its Steam id"
        SaveMatchHow.GOG -> "matched by its GOG id"
        SaveMatchHow.SCRAPER -> "matched by an id the scraper found"
        SaveMatchHow.FOLDER -> "matched by its install folder"
        SaveMatchHow.TITLE -> "matched by its title"
        SaveMatchHow.YOU -> "chosen by you"
        else -> "matched by what it is"
    }
}

object SaveMatcher {
    /**
     * The first rung of [identity] that [index] has a rule for: Steam id, GOG id, a scraper's id, the install
     * folder, the exact title key. Pure; the order is the test.
     */
    fun match(identity: SaveIdentity, index: SaveIndex): SaveMatch? {
        identity.steamId?.let { id -> index.find(GameIdKind.STEAM, id)?.let { return SaveMatch(it, SaveMatchHow.STEAM) } }
        identity.gogId?.let { id -> index.find(GameIdKind.GOG, id)?.let { return SaveMatch(it, SaveMatchHow.GOG) } }
        for (kind in listOf(GameIdKind.STEAM, GameIdKind.GOG, GameIdKind.LUTRIS, GameIdKind.IGDB)) {
            val id = identity.scraped[kind] ?: continue
            if ((kind == GameIdKind.STEAM && id == identity.steamId) || (kind == GameIdKind.GOG && id == identity.gogId)) continue
            index.find(kind, id)?.let { return SaveMatch(it, SaveMatchHow.SCRAPER) }
        }
        identity.installFolder?.let { folder -> index.find(SaveMatchHow.FOLDER, folder)?.let { return SaveMatch(it, SaveMatchHow.FOLDER) } }
        identity.titleKey?.let { key -> index.find(SaveMatchHow.TITLE, key)?.let { return SaveMatch(it, SaveMatchHow.TITLE) } }
        return null
    }
}

/**
 * The save templates the person's paired computers answered with, kept by the agent core beside each game's
 * baseline (`<computer folder>/saves/<game>.spec.json`, droidtop-agent core savesync `spec_path`). This is the seam
 * until the agent core hands droidtop the Ludusavi index itself (Droidtop/tracker#546, slice 1): the interface is
 * [SaveIndex], so the index replaces this class and nothing else. A computer is asked for a game's saves only by a
 * sync, so a game never synced with any computer has no entry here.
 */
class ComputerSpecs(private val context: Context) : SaveIndex {
    private fun files(key: String): List<File> = Computers.list(context).map { computer ->
        File(Computers.stateDir(context, computer), "saves/${ComputerSaves.fileKey(key)}.spec.json")
    }.filter { it.isFile }

    /** The game key a store id is known by on every device. */
    private fun keyOf(kind: String, value: String): String? = when (kind) {
        GameIdKind.STEAM -> "steam:$value"
        GameIdKind.GOG -> "gog:$value"
        else -> null
    }

    override fun find(kind: String, value: String): String? = keyOf(kind, value)?.takeIf { files(it).isNotEmpty() }

    /** Whether a computer answered with a rule for the game keyed [key]. */
    fun has(key: String): Boolean = files(key).isNotEmpty()

    /** The templates every computer gave for [key], unioned and in order. Empty when none did. */
    fun patterns(key: String): List<String> = files(key).flatMap { file ->
        runCatching {
            val array = JSONObject(file.readText()).optJSONArray("patterns")
            (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
        }.getOrDefault(emptyList())
    }.distinct()
}

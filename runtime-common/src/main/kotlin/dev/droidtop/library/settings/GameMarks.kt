package dev.droidtop.library.settings

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Where a person is with a game (Droidtop/tracker#722), as Playnite, LaunchBox and Steam let one say. */
enum class PlayStatus(val id: String, val label: String) {
    NONE("", "Not set"),
    PLAYING("playing", "Playing"),
    COMPLETED("completed", "Completed"),
    BACKLOG("backlog", "Backlog"),
    DROPPED("dropped", "Dropped"),
    ;

    /** The next status a press turns to, round the list. */
    fun next(): PlayStatus = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(id: String?): PlayStatus = entries.firstOrNull { it.id == id && it != NONE } ?: NONE
    }
}

/** One game's marks: the person's own rating, 0 (none) to 5 stars, and their status. */
data class GameMark(val stars: Int = 0, val status: PlayStatus = PlayStatus.NONE) {
    val empty: Boolean get() = stars == 0 && status == PlayStatus.NONE
}

/**
 * The person's own marks per game (Droidtop/tracker#722): kept apart from the scraped fields (a scrape never touches
 * them, and the scraped rating stays the scraped one), in a file of droidtop's own that the backup carries
 * (`DroidtopBackup.FILES`). Pure parse and format; the store's reads and writes are small, off the main thread.
 */
object GameMarks {
    const val FILE = "game-marks.json"

    /** "★★★☆☆", or "Not rated" for none. Pure. */
    fun starsLine(stars: Int): String =
        if (stars <= 0) "Not rated" else "★".repeat(stars.coerceAtMost(5)) + "☆".repeat(5 - stars.coerceAtMost(5))

    fun parse(text: String?): Map<String, GameMark> {
        val json = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { id ->
            val row = json.optJSONObject(id) ?: return@mapNotNull null
            val mark = GameMark(row.optInt("stars", 0).coerceIn(0, 5), PlayStatus.of(row.optString("status", "")))
            if (mark.empty) null else id to mark
        }.toMap()
    }

    fun format(marks: Map<String, GameMark>): String = JSONObject().apply {
        marks.filterValues { !it.empty }.forEach { (id, mark) ->
            put(id, JSONObject().put("stars", mark.stars).put("status", mark.status.id))
        }
    }.toString()

    private fun file(context: Context) = File(context.filesDir, FILE)

    /**
     * The marks in memory, for the lists' filters and sorts (Droidtop/tracker#722), so a list never reads the file while
     * it filters: [warm]ed once off the main thread at start and kept current by [set].
     */
    @Volatile private var cache: Map<String, GameMark> = emptyMap()

    /** Reads the file into memory; call off the main thread. */
    fun warm(context: Context) {
        cache = all(context)
    }

    /** [gameId]'s marks from memory; empty until [warm] ran. */
    fun cached(gameId: String): GameMark = cache[gameId] ?: GameMark()

    fun all(context: Context): Map<String, GameMark> = parse(runCatching { file(context).readText() }.getOrNull())

    fun of(context: Context, gameId: String): GameMark = all(context)[gameId] ?: GameMark()

    @Synchronized
    fun set(context: Context, gameId: String, mark: GameMark) {
        val updated = all(context).let { if (mark.empty) it - gameId else it + (gameId to mark) }
        cache = updated
        runCatching {
            val target = file(context)
            val temp = File(target.parentFile, "$FILE.tmp")
            temp.writeText(format(updated))
            if (!temp.renameTo(target)) target.writeText(format(updated))
        }
    }
}

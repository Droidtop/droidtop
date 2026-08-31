package dev.droidtop.library.consoles

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * User-driven refresh of the player database from the droidtop platform-db
 * repository (docs/SPEC.md §7h) — the separately-updatable half of
 * [KnownPlayers]' two-source model. Plain [HttpURLConnection], the same
 * real pattern every scraper client in this module already uses.
 *
 * The download is validated as a real database (parses, has a `players`
 * array) BEFORE it replaces anything, and written atomically
 * (temp + rename) — a failed or garbage download can never brick player
 * resolution, and [KnownPlayers] additionally ignores an unparseable
 * on-disk copy as its own second line of defense.
 */
object PlayersDatabaseUpdater {
    private const val DB_FILE_NAME = "players-database.json"

    /** Returns the number of players in the refreshed database. */
    fun update(context: Context, url: String = PlatformDatabaseSource.urlFor(context, DB_FILE_NAME)): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val text = try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} from $url" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val playerCount = JSONObject(text).getJSONArray("players").length()
        check(playerCount > 0) { "Downloaded database has no players — not replacing the current one" }

        val dest = File(context.filesDir, DB_FILE_NAME)
        val temp = File(context.filesDir, "$DB_FILE_NAME.downloading")
        temp.writeText(text)
        check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
            "Couldn't move the downloaded database into place"
        }
        KnownPlayers.invalidate()
        return playerCount
    }
}

package dev.droidtop.library.consoles

import android.content.Context

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
    fun update(context: Context, url: String = PlatformDatabaseSource.urlFor(context, DB_FILE_NAME)): Int =
        install(context, PlatformDatabaseTransport.get(url))

    /**
     * Parses [text] with the same parser [KnownPlayers] reads it with, so a
     * row it would reject (a missing `pkg`, say) fails here rather than
     * being written and then silently ignored in favour of the seed.
     * Returns the player count; throws when [text] is not a usable database.
     */
    fun validate(text: String): Int {
        val playerCount = KnownPlayers.parse(text).size
        check(playerCount > 0) { "Downloaded database has no players — not replacing the current one" }
        return playerCount
    }

    /** Validates [text] and only then replaces the current copy; see [dev.droidtop.library.EnginesDatabase.install]. */
    fun install(context: Context, text: String): Int {
        val playerCount = validate(text)
        PlatformDatabaseTransport.replace(context, DB_FILE_NAME, text)
        KnownPlayers.invalidate()
        return playerCount
    }
}

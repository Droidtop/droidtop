package dev.droidtop.library.f95checker

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryGameGroup

/**
 * Importing the F95Checker watch list (docs/SPEC.md 7g, "The watch list,
 * imported once"): the one other source that can say which F95zone thread
 * a game is, because the user already told F95Checker every thread they
 * watch.
 *
 * What is read, and how, is F95Checker's own database (its `modules/db.py`):
 * one `games` table whose row `id` IS the F95zone thread id -- its
 * `create_game` inserts `thread.id` as the row's own id -- while custom
 * rows (a game with no thread) carry a negative id and `custom` set. The
 * same facts the user's own Pythia reads it by
 * (`plugin_sources/library/f95/plugin.py`: a read-only connection,
 * negative ids skipped); this is a reimplementation against droidtop's own
 * model, not a copy of either.
 *
 * What is imported is only the thread links. The name and the two versions
 * F95Checker keeps are evidence for matching, never data to write, and
 * nothing is linked by the import itself: every pairing it offers is for a
 * person to confirm (SPEC 7g).
 */
object F95CheckerImport {

    /**
     * One row of the watch list that names a thread: the thread's id (the
     * row's own `id`), the game's name as F95Checker's page parser cleaned
     * it, and the two version strings it keeps -- `version`, the thread's
     * newest, and `installed`, the version the user marked installed there.
     */
    data class WatchedGame(
        val threadId: Long,
        val name: String,
        val version: String?,
        val installed: String?,
    )

    /** What reading the picked database produced. */
    sealed interface WatchList {
        /** [games] in the database's own row order; [skipped] counts rows that name no thread. */
        data class Read(val games: List<WatchedGame>, val skipped: Int) : WatchList

        /** Why nothing could be read, in words a person can read. */
        data class Failed(val reason: String) : WatchList
    }

    /**
     * The version a database column names, or null for the values that say
     * it has none: blank, F95Checker's own `Unchecked` default (db.py's
     * column default) and the index's `N/A`, the same two cases
     * [GameUpdates.available] already reads as "no version to give".
     */
    fun versionOf(raw: String?): String? = raw?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("Unchecked", ignoreCase = true) && !it.equals("N/A", ignoreCase = true) }

    /**
     * One row of the `games` table, or null for a row that names no thread
     * and cannot be linked to anything: F95Checker gives custom games a
     * negative id and sets `custom` (db.py's `create_game`), and a row with
     * no name has nothing to match a library game by.
     */
    fun watchRow(id: Long, custom: Long, name: String?, version: String?, installed: String?): WatchedGame? {
        if (id <= 0L || custom != 0L) return null
        val title = name?.trim().orEmpty()
        if (title.isEmpty()) return null
        return WatchedGame(id, title, versionOf(version), versionOf(installed))
    }

    /**
     * Reads the watch list out of the database the user picked. The file is
     * opened READ-ONLY straight from the picked document's own descriptor
     * (`/proc/self/fd`, `SQLiteDatabase`'s `OPEN_READONLY`) -- the same
     * stance Pythia's `?mode=ro` connection takes, so a file F95Checker
     * itself may have open is never written to or locked: never copied
     * anywhere, never held open past this read of five columns.
     *
     * Blocking database work: both settings surfaces run a document pick's
     * [dev.droidtop.library.settings.DocumentPickItem] handler on IO
     * (docs/SPEC.md settings architecture, "never on the main thread"), and
     * that is the only kind of caller this has.
     */
    fun readWatchList(context: Context, uri: Uri): WatchList {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (t: Throwable) {
            return WatchList.Failed("That file could not be opened (${t.message ?: t.javaClass.simpleName})")
        }
        if (descriptor == null) return WatchList.Failed("The file picker gave no file")
        return descriptor.use { picked ->
            val database = try {
                SQLiteDatabase.openDatabase("/proc/self/fd/${picked.fd}", null, SQLiteDatabase.OPEN_READONLY)
            } catch (t: Throwable) {
                return WatchList.Failed("That file is not a database (${t.message ?: t.javaClass.simpleName})")
            }
            database.use { db ->
                try {
                    db.rawQuery("SELECT id, custom, name, version, installed FROM games", null).use(::readRows)
                } catch (t: Throwable) {
                    WatchList.Failed("That database holds no F95Checker watch list (${t.message ?: t.javaClass.simpleName})")
                }
            }
        }
    }

    private fun readRows(rows: Cursor): WatchList {
        val games = mutableListOf<WatchedGame>()
        var skipped = 0
        while (rows.moveToNext()) {
            val row = watchRow(rows.getLong(0), rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4))
            if (row == null) skipped++ else games += row
        }
        return WatchList.Read(games, skipped)
    }

    /**
     * A watch row and the library game it names, with the evidence for the
     * pairing stated as facts, never as a verdict: the screen says both and
     * the person decides (SPEC 7g).
     */
    data class ProposedLink(
        val watch: WatchedGame,
        val game: LibraryGameGroup,
        /**
         * One of the watch row's versions is one of the game's own folder
         * versions, compared the way [GameUpdates] compares versions. A
         * corroborated name match is the only pairing the screen marks
         * before the person has looked at it.
         */
        val corroborated: Boolean,
        /**
         * The pairing is not unique: another watch row names this same
         * game, or this row names more than one game. Never marked, whoever
         * does the marking.
         */
        val ambiguous: Boolean,
    ) {
        /**
         * Whether the import screen marks this row before the person has
         * looked: a corroborated, unambiguous name match on a game that
         * links no other thread. Marking is only a pre-fill -- nothing is
         * linked until the person imports the marked rows.
         */
        val recommended: Boolean get() = corroborated && !ambiguous && game.f95Thread == null
    }

    /** What [match] found: the offered pairings, and the rows it could not offer. */
    data class Matches(
        val offered: List<ProposedLink>,
        /** Watch rows whose game already links their thread: nothing to import. */
        val alreadyLinked: Int,
        /** Watch rows that name no game in this library. */
        val unmatched: Int,
    )

    /**
     * Pairs each watch row with the library game it names. A row names a
     * game when the two names are equal the way [GameNaming.nameKey]
     * compares names -- case and punctuation aside, the same equality that
     * already decides two folders are one game; a name that is merely
     * similar never matches, for the same reason the scan's own grouping
     * never merges merely similar folder names. Corroboration is
     * [GameUpdates]' own version comparison, less a leading `v`, against
     * every version the game's folders name.
     *
     * One pass over the groups builds the name index and one lookup per
     * row answers: nothing here grows with the product of the two lists.
     */
    fun match(watch: List<WatchedGame>, groups: List<LibraryGameGroup>): Matches {
        val gamesByName = HashMap<String, MutableList<LibraryGameGroup>>()
        for (group in groups) {
            val key = GameNaming.nameKey(group.game.name)
            if (key.isNotEmpty()) gamesByName.getOrPut(key) { mutableListOf() }.add(group)
        }
        val rowsByName = HashMap<String, Int>()
        for (row in watch) {
            val key = GameNaming.nameKey(row.name)
            if (key.isNotEmpty()) rowsByName[key] = (rowsByName[key] ?: 0) + 1
        }
        val offered = mutableListOf<ProposedLink>()
        var alreadyLinked = 0
        var unmatched = 0
        for (row in watch) {
            val key = GameNaming.nameKey(row.name)
            val named = if (key.isEmpty()) emptyList() else gamesByName[key].orEmpty()
            if (named.isEmpty()) {
                unmatched++
                continue
            }
            val rowAmbiguous = (rowsByName[key] ?: 0) > 1
            var offeredAny = false
            for (game in named) {
                if (game.f95Thread == row.threadId) {
                    alreadyLinked++
                    offeredAny = true
                    continue
                }
                offered += ProposedLink(row, game, corroborated(row, game), rowAmbiguous || named.size > 1)
                offeredAny = true
            }
            if (!offeredAny) unmatched++
        }
        return Matches(offered, alreadyLinked, unmatched)
    }

    /** Either of the row's versions is one of the game's, as [GameUpdates] writes and compares them. */
    private fun corroborated(row: WatchedGame, game: LibraryGameGroup): Boolean {
        val theirs = listOfNotNull(row.version, row.installed).map(GameUpdates::normalize).filter { it.isNotEmpty() }
        if (theirs.isEmpty()) return false
        val ours = game.game.allVersions.map { GameUpdates.normalize(it.version) }.filter { it.isNotEmpty() }
        if (ours.isEmpty()) return false
        return ours.any { folderVersion -> theirs.any { it.equals(folderVersion, ignoreCase = true) } }
    }

    /**
     * One line for the import screen's row: both sides of the pairing, so
     * the person confirms against the evidence rather than the fact that a
     * row exists.
     */
    fun linkLine(link: ProposedLink): String {
        val watch = link.watch
        val line = StringBuilder()
        if (watch.name != link.game.game.name) line.append("F95Checker calls it \"${watch.name}\". ")
        line.append(
            when {
                watch.version != null && watch.installed != null -> "F95Checker: ${watch.version}, ${watch.installed} installed there. "
                watch.version != null -> "F95Checker: ${watch.version}. "
                watch.installed != null -> "F95Checker: ${watch.installed} installed there. "
                else -> "F95Checker's row names no version. "
            },
        )
        val versions = link.game.game.allVersions.map { it.version }.filter { it.isNotBlank() }.distinct()
        line.append(
            if (versions.isEmpty()) "This game's folders name no version."
            else "This game's folders hold ${versions.joinToString(", ")}.",
        )
        link.game.f95Thread?.let { thread ->
            line.append(" Linked to thread $thread already; importing would replace that link.")
        }
        if (link.ambiguous) line.append(" Another row or game shares this name; check this is the one you mean.")
        return line.toString()
    }
}

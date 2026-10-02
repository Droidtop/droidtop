package dev.droidtop.app.settings

import android.content.Context
import android.net.Uri
import dev.droidtop.app.LibraryCore
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.f95checker.F95CheckerImport
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.ToggleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Import from F95Checker" screen (docs/SPEC.md 7g, "The watch list,
 * imported once"): read the F95Checker watch-list database the user picks,
 * show every name match with its evidence, and link only what the person
 * marks. A catalog screen like the other management screens, so both
 * Settings surfaces chrome the same data (docs/SPEC.md settings
 * architecture) and there is no second UI to keep honest.
 *
 * The screen holds the watch list and the marks between visits, because a
 * person picks, reads and decides in one sitting; an import clears both,
 * and a process death clears both too, having written nothing unconfirmed.
 */
object F95ImportCatalog {

    /** What was read from the picked database, and what it matched: one sitting's state, never persisted. */
    private var watch: F95CheckerImport.WatchList.Read? = null
    private var matches: F95CheckerImport.Matches? = null
    private val accepted = mutableSetOf<String>()

    fun screen() = CatalogScreen(
        id = AppSettingsCatalogs.SCREEN_F95_IMPORT,
        title = "Import from F95Checker",
        subtitle = "Copy the F95zone thread links your F95Checker watch list already knows, without pasting them one by one",
        groups = { context -> groups(context) },
    )

    private suspend fun groups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val read = watch
        val database = CatalogGroup(
            id = "f95_database",
            title = "The database",
            items = buildList {
                add(pickRow())
                if (read != null) add(summaryRow(read))
            },
        )
        if (read == null) return@withContext listOf(database)
        val found = matches ?: matchAgainstLibrary(context, read)
        if (found == null) {
            // The library's first scan is still walking: nothing honest to
            // match against yet, and this row's press re-reads the screen.
            return@withContext listOf(
                database,
                CatalogGroup(
                    id = "f95_matches",
                    title = "Matches",
                    items = listOf(
                        ActionItem(
                            id = "f95_matches_wait",
                            title = "Scanning games",
                            run = {},
                        ),
                    ),
                ),
            )
        }
        buildList<CatalogGroup> {
            add(database)
            val (corroborated, possible) = found.offered.partition { it.corroborated && !it.ambiguous }
            if (corroborated.isNotEmpty()) add(linksGroup("f95_matches", "Matches", corroborated))
            if (possible.isNotEmpty()) add(linksGroup("f95_possible", "Possible matches", possible))
            if (found.offered.isEmpty() && read.games.isNotEmpty()) {
                // offered empty means every row either names a game its
                // own thread is already linked to or names nothing here;
                // the row must say which, not claim "nothing matched".
                add(
                    CatalogGroup(
                        id = "f95_matches",
                        title = "Matches",
                        items = listOf(
                            ActionItem(
                                id = "f95_matches_none",
                                title = when {
                                    found.alreadyLinked == 0 ->
                                        "None of the ${read.games.size} watched games names a game in this library"
                                    found.unmatched == 0 ->
                                        "Every game this watch list names already links its F95zone thread"
                                    else ->
                                        "${found.alreadyLinked} already linked; the other ${found.unmatched} watched games name no game in this library"
                                },
                                subtitle = if (found.alreadyLinked == 0) {
                                    "A game whose name is written differently there can still be linked by hand: " +
                                        "paste the thread link on the game's detail, where you can see the game you are linking"
                                } else {
                                    "Nothing to import from this watch list"
                                },
                                run = {},
                            ),
                        ),
                    ),
                )
            }
            if (found.offered.isNotEmpty()) add(importGroup(read, found))
        }
    }

    private fun pickRow() = DocumentPickItem(
        id = "f95_pick",
        title = "Choose F95Checker's database",
        // Not a database mime type: file managers label a .sqlite3 as
        // application/octet-stream or nothing at all, which a narrower
        // filter would hide. The file is opened as a database before
        // anything is kept, and refused plainly when it is not one.
        mimeType = "*/*",
        onPicked = ::pickDatabase,
    )

    /** Reads the picked file; the renderer runs this off the main thread, and it only reads. */
    private fun pickDatabase(context: Context, uri: Uri): String {
        return when (val result = F95CheckerImport.readWatchList(context, uri)) {
            is F95CheckerImport.WatchList.Failed -> {
                watch = null
                matches = null
                accepted.clear()
                result.reason
            }
            is F95CheckerImport.WatchList.Read -> {
                watch = result
                matches = null
                accepted.clear()
                if (result.games.isEmpty()) {
                    "That watch list is empty"
                } else {
                    "Read ${result.games.size} watched games" +
                        (if (result.skipped > 0) " (${result.skipped} entries carry no thread)" else "")
                }
            }
        }
    }

    private fun summaryRow(read: F95CheckerImport.WatchList.Read) = ActionItem(
        id = "f95_summary",
        title = "Watch list",
        subtitle = "Pick the database again to start over with another file",
        value = "${read.games.size} games" + (if (read.skipped > 0) ", ${read.skipped} without a thread" else ""),
        run = {},
    )

    /**
     * The matches for what was read, against the library the library has
     * already published (`backgroundScanState` over the game kinds):
     * matching is a pass over a list in memory, and a walk is never
     * started for it -- the one already running is joined (the widget
     * provider's own pattern, same reason: never an empty answer while a
     * first scan is still walking).
     */
    private suspend fun matchAgainstLibrary(
        context: Context,
        read: F95CheckerImport.WatchList.Read,
    ): F95CheckerImport.Matches? {
        val entries = libraryGames(context) ?: return null
        val found = F95CheckerImport.match(read.games, LibraryGrouping.group(entries))
        matches = found
        accepted.clear()
        accepted += found.offered.filter { it.recommended }.map(::rowKey)
        return found
    }

    private suspend fun libraryGames(context: Context): List<LibraryEntry>? {
        val library = LibraryCore.library(context)
        val state = library.backgroundScanState(LibraryKinds.GAMES)
        return state.value ?: run {
            library.scanInBackground(LibraryKinds.GAMES)
            withTimeoutOrNull(WAIT_FOR_LIBRARY_MS) { state.filterNotNull().first() }
        }
    }

    /** One offered pairing's identity: a thread can name more than one game, so the game is half of the key. */
    private fun rowKey(link: F95CheckerImport.ProposedLink): String =
        "${link.watch.threadId}::${link.game.entriesByPath.keys.sorted().firstOrNull().orEmpty()}"

    private fun linksGroup(id: String, title: String, links: List<F95CheckerImport.ProposedLink>): CatalogGroup =
        CatalogGroup(
            id = id,
            title = title,
            items = links.map { link ->
                val key = rowKey(link)
                ToggleItem(
                    id = "f95_row_$key",
                    title = link.game.game.name,
                    subtitle = "Thread ${link.watch.threadId}. " + F95CheckerImport.linkLine(link),
                    current = key in accepted,
                    onToggle = { _, on ->
                        if (on) accepted.add(key) else accepted.remove(key)
                    },
                )
            },
        )

    private fun importGroup(read: F95CheckerImport.WatchList.Read, found: F95CheckerImport.Matches): CatalogGroup {
        val marked = found.offered.count { rowKey(it) in accepted }
        return CatalogGroup(
            id = "f95_import",
            title = "Import",
            items = listOf(
                AsyncActionItem(
                    id = "f95_import_go",
                    title = "Import the marked links",
                    subtitle = "Each match switched on is linked to its thread; $marked of ${found.offered.size} are on" +
                        (if (found.unmatched > 0) "; ${found.unmatched} of the ${read.games.size} watched games name no game here" else "") +
                        (if (found.alreadyLinked > 0) "; ${found.alreadyLinked} already linked" else ""),
                    // Confirming once for the whole batch is the "check the
                    // matches" step made real: every switch was shown, and
                    // this press is the person's yes.
                    confirmTitle = if (marked > 0) "Link $marked games to their F95zone threads?" else null,
                    run = ::importMarked,
                ),
            ),
        )
    }

    /**
     * Links every marked pairing through the ONE write path a pasted link
     * uses (`Library.linkF95Thread`: every folder of the game at once, and
     * the ask about the new thread at once), then clears the sitting: what
     * was imported is linked, and the screen goes back to asking for a
     * database rather than showing a second copy of state that is now
     * stale.
     */
    private suspend fun importMarked(context: Context, onStatus: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val found = matches ?: return@withContext "No watch list has been read yet"
            val marked = found.offered.filter { rowKey(it) in accepted }
            if (marked.isEmpty()) return@withContext "Nothing is marked: mark the matches you want to link first"
            val library = LibraryCore.library(context)
            var unasked = 0
            for ((index, link) in marked.withIndex()) {
                onStatus("Linking ${index + 1} of ${marked.size}")
                if (library.linkF95Thread(link.game.entriesByPath.keys, link.watch.threadId) != null) unasked++
            }
            watch = null
            matches = null
            accepted.clear()
            "Linked ${marked.size} games to their F95zone threads" +
                (if (unasked == 0) "" else "; $unasked new threads could not be asked about right now (they are asked again later)")
        }

    /** How long the screen waits for a first library scan to publish before saying it is still running. */
    private const val WAIT_FOR_LIBRARY_MS = 15_000L
}

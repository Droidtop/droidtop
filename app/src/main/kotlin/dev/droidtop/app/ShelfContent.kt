package dev.droidtop.app

import android.content.Context
import dev.droidtop.library.CollectionMembership
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a Shelf widget shows (Droidtop/tracker#563), chosen in its maker ([ShelfConfigureActivity]) and kept by the
 * widget's id ([WidgetConfigs]): a [Kind] with its argument (a collection's or a system's id) and a [Layout].
 */
internal data class ShelfContent(val kind: Kind, val arg: String? = null, val layout: Layout = Layout.GRID) {
    enum class Kind { RECENT, ADDED, FAVOURITES, COLLECTION, SYSTEM }
    enum class Layout { LIST, GRID }

    /** One choice the maker offers: what it shows and its label ("Recently played", "Favourites", "SNES"). */
    class Choice(val kind: Kind, val arg: String?, val label: String)

    companion object {
        /** How many games a shelf holds; the grid scrolls, the list shows its first four. */
        const val LIMIT = 24

        /**
         * The games [content] shows, from the library's in-memory index and, for a collection, one read of the two
         * membership tables. Hidden and missing games are left out. Off the main thread.
         */
        suspend fun entries(context: Context, content: ShelfContent): List<LibraryEntry> {
            val games = DroidtopWidgets.libraryGames(context).filter { !it.hidden && !it.missing }
            val chosen = when (content.kind) {
                Kind.RECENT -> games.filter { it.lastPlayedEpochMs != null }.sortedByDescending { it.lastPlayedEpochMs }
                Kind.ADDED -> games.filter { it.firstSeenEpochMs > 0L }.sortedByDescending { it.firstSeenEpochMs }
                Kind.FAVOURITES -> games.filter { it.favorite }.sortedBy { name(it).lowercase() }
                Kind.SYSTEM -> games.filter { it.systemId == content.arg }.sortedBy { name(it).lowercase() }
                Kind.COLLECTION -> {
                    val members = membership(context)?.byCollection?.get(content.arg).orEmpty().toSet()
                    games.filter { it.id in members }.sortedBy { name(it).lowercase() }
                }
            }
            return chosen.take(LIMIT)
        }

        /** The title a shelf shows over its games. */
        suspend fun title(context: Context, content: ShelfContent): String =
            choices(context).firstOrNull { it.kind == content.kind && it.arg == content.arg }?.label ?: "Games"

        /** Every shelf the maker can offer: the built-in ones, then each collection, then each system with games. */
        suspend fun choices(context: Context): List<Choice> {
            val games = DroidtopWidgets.libraryGames(context)
            val collections = membership(context)?.collections.orEmpty().sortedBy { it.name.lowercase() }
            val systemIds = games.mapNotNullTo(HashSet()) { it.systemId }
            val systems = if (systemIds.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                ConsoleSystemsRepository.allSystems(context).filter { it.id in systemIds }.sortedBy { it.displayName.lowercase() }
            }
            return listOf(
                Choice(Kind.RECENT, null, "Recently played"),
                Choice(Kind.ADDED, null, "Recently added"),
                Choice(Kind.FAVOURITES, null, "Favourites"),
            ) + collections.map { Choice(Kind.COLLECTION, it.id, it.name) } +
                systems.map { Choice(Kind.SYSTEM, it.id, it.displayName) }
        }

        fun name(entry: LibraryEntry): String = GameNaming.displayName(entry.title)

        private suspend fun membership(context: Context): CollectionMembership? =
            withTimeoutOrNull(MEMBERSHIP_WAIT_MS) { CollectionMembership.flow(context).first() }

        private const val MEMBERSHIP_WAIT_MS = 5_000L
    }
}

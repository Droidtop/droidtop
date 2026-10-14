package dev.droidtop.library.stores

import android.content.Context
import dev.droidtop.library.StoreIdentity
import java.io.File

/**
 * One game on a store account's wishlist: the store's own id, its name and its cover when the store says.
 * Not owned, and never made up: a store with no wishlist to read has none (docs/SPEC.md 7g, "Wishlist").
 */
data class WishlistGame(val gameId: String, val title: String, val artUrl: String? = null)

/**
 * droidtop's own copy of each store's wishlist (docs/SPEC.md 7g, "Wishlist", Droidtop/tracker#134): written by the
 * store's sync, read by [StoreLibrary.wishlist] with no network. One small file per store in the app's private
 * storage, a line per game ("id TAB title TAB cover"), so a rebuild of the library reads files that are already
 * there. Pure parsing and formatting, plus the file work, which the callers do off the main thread.
 */
object Wishlist {
    private const val DIRECTORY = "wishlist"

    /** The cap a store reads in one sync: a wishlist is a shelf, not a catalogue. */
    const val LIMIT = 200

    fun file(context: Context, storeId: String): File = File(File(context.filesDir, DIRECTORY), "$storeId.tsv")

    private fun clean(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun format(games: List<WishlistGame>): String =
        games.joinToString("") { "${clean(it.gameId)}\t${clean(it.title)}\t${clean(it.artUrl.orEmpty())}\n" }

    fun parse(text: String): List<WishlistGame> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        val id = parts.getOrNull(0)?.trim().orEmpty()
        val title = parts.getOrNull(1)?.trim().orEmpty()
        if (id.isEmpty() || title.isEmpty()) null else WishlistGame(id, title, parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() })
    }.toList()

    fun read(context: Context, storeId: String): List<WishlistGame> =
        runCatching { file(context, storeId).takeIf { it.isFile }?.readText()?.let(::parse) }.getOrNull().orEmpty()

    /** Replaces the copy with [games], through a temporary file so a read never sees half of one. */
    fun write(context: Context, storeId: String, games: List<WishlistGame>) {
        val target = file(context, storeId)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(format(games))
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    /** Forgets the copy (a sign-out). */
    fun clear(context: Context, storeId: String) {
        file(context, storeId).delete()
    }

    /** [games] as the library's rows for [storeId]: not installed, held as [StoreHolding.WISHLIST]. */
    fun rows(storeId: String, games: List<WishlistGame>): List<StoreGame> = games.map { game ->
        StoreGame(
            store = storeId,
            gameId = game.gameId,
            title = game.title,
            installed = false,
            installPath = null,
            sizeBytes = 0L,
            artUrl = game.artUrl,
            holding = StoreHolding.WISHLIST,
        )
    }

    /**
     * The wishlist rows [wished] that are games the person does not already have: a row whose title is spelled the
     * same way ([StoreIdentity.titleKey]) as any of [owned] is dropped, in this store or another, because the
     * shelf is for games the person does not own and a wished game they do own would otherwise fold into
     * the owned card. One hash set: linear in the rows.
     */
    fun notOwned(wished: List<StoreGame>, owned: List<StoreGame>): List<StoreGame> {
        val have = owned.mapNotNullTo(HashSet()) { StoreIdentity.titleKey(it.title).takeIf { key -> key.isNotEmpty() } }
        val haveIds = owned.mapTo(HashSet()) { it.key }
        return wished.filter { row -> row.key !in haveIds && StoreIdentity.titleKey(row.title) !in have }
    }
}

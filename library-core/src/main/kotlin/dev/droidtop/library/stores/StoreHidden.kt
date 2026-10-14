package dev.droidtop.library.stores

import android.content.Context
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.consoles.RomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A store's own hidden-games list as droidtop's Hidden (docs/SPEC.md 7g,
 * "Collections", Droidtop/tracker#402): a game the person hid in Steam is
 * hidden here too, through the one Hidden rule every list already follows
 * (the `hidden` field of the game's `game_metadata` row, §7j).
 *
 * Only what changes in the store's list is carried over, so the person's own
 * choice in droidtop is never fought: a game that joins the store's list is
 * hidden, a game that leaves it is shown again only when this mirror was what
 * hid it (it was in the list the last read saw), and a game the person shows
 * again in droidtop while the store still hides it stays shown until the
 * store's list changes for that game. Read-only: nothing goes back to the store.
 */
object StoreHidden {
    private const val PREFS = "store_hidden_mirror"

    /** What one read changes: the library ids to hide and the ones to show again. */
    data class Plan(val hide: Set<String>, val show: Set<String>)

    /**
     * Pure. [before] is the store's list as the last read saw it (null when
     * there was none), [now] the list this read gave; both are the store's own
     * game ids, and the plan names library ids (`steam:440`).
     */
    fun plan(storeId: String, before: Set<String>?, now: Set<String>): Plan {
        val previous = before.orEmpty()
        return Plan(
            hide = now.filterTo(HashSet()) { it !in previous }.mapTo(HashSet()) { "$storeId:$it" },
            show = previous.filterTo(HashSet()) { it !in now }.mapTo(HashSet()) { "$storeId:$it" },
        )
    }

    /** Carries [store]'s hidden list over; false when the store gave none (nothing changes). Off the main thread. */
    suspend fun mirror(context: Context, store: StoreLibrary): Boolean = withContext(Dispatchers.IO) {
        val now = store.hiddenGames(context) ?: return@withContext false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = prefs.getStringSet(store.id, null)
        val plan = plan(store.id, before, now)
        val dao = RomDatabase.get(context).romDao()
        (plan.hide.map { it to true } + plan.show.map { it to false }).forEach { (id, hidden) ->
            val current = dao.getGameMetadataSingle(id) ?: GameMetadataEntity(id = id)
            if (current.hidden != hidden) dao.upsertGameMetadata(current.copy(hidden = hidden))
        }
        // A copy: the set the preferences hand back is the one they keep.
        prefs.edit().putStringSet(store.id, HashSet(now)).apply()
        true
    }
}

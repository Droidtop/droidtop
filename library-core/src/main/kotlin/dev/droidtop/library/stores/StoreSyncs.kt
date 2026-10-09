package dev.droidtop.library.stores

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one way a store's library is read again, whoever asks (docs/SPEC.md
 * 7g, "Stores"): the Stores place's Sync library row, its Sync all row, a
 * sign-in that reads the library as it ends. It runs [StoreLibrary.sync] and
 * records when it last succeeded, so every store has a last-synced time and
 * the services need not keep one of their own (they kept it in memory, so it
 * was gone at the next start), and copies the store's collections in
 * ([StoreCollections]). A failed read records nothing: the time says when the
 * library was last read, not when someone last asked.
 */
object StoreSyncs {
    private const val PREFS = "store_library_sync"

    /**
     * Reads [store]'s library off the main thread and, when that worked, records
     * the time. Success carries the game count and what changed against the
     * rows the store held before the read ([SyncChange]).
     */
    suspend fun run(context: Context, store: StoreLibrary): Result<SyncChange> = withContext(Dispatchers.IO) {
        val before = rowKeys(context, store)
        store.sync(context).map { count ->
            record(context, store.id)
            // The store's own groupings follow its library; a failure here never fails the read.
            runCatching { StoreCollections.import(context, store) }
            // A freshly read library is the moment to look for updates of the games set to keep themselves current.
            StoreAutoUpdates.request(context)
            SyncChange.between(count, before, rowKeys(context, store))
        }
    }

    /** The store's rows as droidtop's own copy has them, by game id; null when they cannot be read. No network. */
    private suspend fun rowKeys(context: Context, store: StoreLibrary): Set<String>? =
        runCatching { store.games(context).mapTo(HashSet()) { it.gameId } }.getOrNull()

    /** When [storeId]'s library was last read successfully through [run], or null if it never was. A preferences read. */
    fun lastSynced(context: Context, storeId: String): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(storeId, 0L).takeIf { it > 0 }

    /**
     * What one read changed (docs/SPEC.md 7j "Places"): how many games the store
     * holds, and how many of its rows are new or gone since the read before.
     * The Stores place's sync line says it; it is a line, not a pop-up.
     */
    data class SyncChange(val total: Int, val added: Int = 0, val removed: Int = 0) {
        /** "1,193 games: 12 new, 2 removed"; just the count when nothing moved or nothing was there before. */
        fun line(): String {
            val count = "%,d %s".format(total, if (total == 1) "game" else "games")
            val parts = listOfNotNull(added.takeIf { it > 0 }?.let { "$it new" }, removed.takeIf { it > 0 }?.let { "$it removed" })
            return if (parts.isEmpty()) count else "$count: ${parts.joinToString(", ")}"
        }

        companion object {
            /** A first read (nothing before) adds nothing: everything it lists was already the person's. */
            fun between(total: Int, before: Set<String>?, after: Set<String>?): SyncChange =
                if (before.isNullOrEmpty() || after == null) {
                    SyncChange(total)
                } else {
                    SyncChange(total, added = after.count { it !in before }, removed = before.count { it !in after })
                }
        }
    }

    private fun record(context: Context, storeId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(storeId, System.currentTimeMillis()).apply()
    }
}

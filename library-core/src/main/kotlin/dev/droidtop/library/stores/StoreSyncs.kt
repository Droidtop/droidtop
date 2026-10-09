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

    /** Reads [store]'s library off the main thread and, when that worked, records the time. Success carries the game count. */
    suspend fun run(context: Context, store: StoreLibrary): Result<Int> = withContext(Dispatchers.IO) {
        store.sync(context).onSuccess {
            record(context, store.id)
            // The store's own groupings follow its library; a failure here never fails the read.
            runCatching { StoreCollections.import(context, store) }
        }
    }

    /** When [storeId]'s library was last read successfully through [run], or null if it never was. A preferences read. */
    fun lastSynced(context: Context, storeId: String): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(storeId, 0L).takeIf { it > 0 }

    private fun record(context: Context, storeId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(storeId, System.currentTimeMillis()).apply()
    }
}

package dev.droidtop.library.stores

import android.content.Context
import dev.droidtop.library.consoles.CollectionEntity
import dev.droidtop.library.consoles.RomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A store's own groupings (Steam collections) as droidtop collections
 * (docs/SPEC.md 7g, "Collections", Droidtop/tracker#232): after a store's
 * library is read, each of its collections becomes a collection named
 * "<store>: <name>" whose members are that store's games, and a collection
 * the store no longer has goes. The copy is the store's: it is made again at
 * each sync, so an edit to such a collection in droidtop lasts until the
 * next one, and nothing is ever written back to the store. The person's own
 * collections are separate and are never touched.
 */
object StoreCollections {
    /** Every imported collection's id starts with this ([dev.droidtop.library.CollectionScope.isImported]). */
    const val IMPORT_PREFIX = "import:"

    /** Whether [collectionId] is a store's collection copied in (and so rewritten at the store's next sync). */
    fun isImported(collectionId: String) = collectionId.startsWith(IMPORT_PREFIX)

    /** The name a person's copy of the imported collection called [importedName] gets: the store's label dropped. Pure. */
    fun copyName(importedName: String) = importedName.substringAfter(": ", importedName).ifBlank { importedName }

    /** The ids of the collections imported from [storeId] start with this. */
    fun prefixFor(storeId: String) = "$IMPORT_PREFIX$storeId:"

    /** The droidtop collection and its members' library ids that [collection] of [store] becomes. Pure. */
    fun plan(storeId: String, storeLabel: String, collection: StoreCollection): Pair<CollectionEntity, List<String>> =
        CollectionEntity(id = prefixFor(storeId) + collection.id, name = "$storeLabel: ${collection.name}") to
            collection.gameIds.sorted().map { "$storeId:$it" }

    /** Copies [store]'s collections in; false when the store gave none to copy (what was imported stays). */
    suspend fun import(context: Context, store: StoreLibrary): Boolean = withContext(Dispatchers.IO) {
        val collections = store.collections(context) ?: return@withContext false
        RomDatabase.get(context).romDao()
            .replaceImportedCollections(prefixFor(store.id), collections.map { plan(store.id, store.label, it) })
        true
    }
}

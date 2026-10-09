package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.consoles.CollectionEntity
import dev.droidtop.library.consoles.CollectionMemberEntity
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.stores.StoreCollections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn

/**
 * Which games are in which collections, as the database says right now
 * (docs/SPEC.md 7g, "A store's collections", and 7i "Collections"): the
 * collections, each collection's members and each game's collections, made
 * from two whole-table reads, never one read per game or per collection.
 */
data class CollectionMembership(
    val collections: List<CollectionEntity> = emptyList(),
    /** Collection id to its members' library ids. */
    val byCollection: Map<String, List<String>> = emptyMap(),
    /** Library id to the ids of the collections it is in. */
    val byEntry: Map<String, Set<String>> = emptyMap(),
) {
    /** The collections [ids] (one card's copies: a merged card is a member if any copy is) are in. */
    fun collectionsOf(ids: Collection<String>): Set<String> =
        if (ids.size == 1) byEntry[ids.first()].orEmpty() else ids.flatMapTo(HashSet()) { byEntry[it].orEmpty() }

    companion object {
        /** Pure: the membership the two tables hold. */
        fun of(collections: List<CollectionEntity>, members: List<CollectionMemberEntity>): CollectionMembership {
            val byCollection = members.groupBy({ it.collectionId }, { it.gameId })
            val byEntry = HashMap<String, MutableSet<String>>()
            members.forEach { byEntry.getOrPut(it.gameId) { HashSet() } += it.collectionId }
            return CollectionMembership(collections, byCollection, byEntry)
        }

        /**
         * The membership, again whenever either table changes (a game added
         * to a collection from its menu, a store's collections imported), so
         * a list collects it like its counts and never asks per game.
         */
        fun flow(context: Context): Flow<CollectionMembership> = kotlinx.coroutines.flow.flow {
            // The database is reached on the collector's IO thread, never while composing.
            val dao = RomDatabase.get(context).romDao()
            emitAll(combine(dao.collectionsFlow(), dao.membersFlow()) { collections, members -> of(collections, members) })
        }.flowOn(Dispatchers.IO)
    }
}

/**
 * The rules for where a collection shows (docs/SPEC.md 7g, "A store's
 * collections"): one place, so Retro and PC Games cannot disagree.
 */
object CollectionScope {
    /** Whether a collection was copied in from a store ([StoreCollections]). */
    fun isImported(collectionId: String): Boolean = collectionId.startsWith(StoreCollections.IMPORT_PREFIX)

    /** The store a collection was imported from, by id; null for the person's own. */
    fun importedFrom(collectionId: String): String? =
        collectionId.takeIf(::isImported)?.removePrefix(StoreCollections.IMPORT_PREFIX)?.substringBefore(':')?.takeIf { it.isNotEmpty() }

    /** An imported collection's own name, without the "<store>: " its stored name starts with. */
    fun shortName(collection: CollectionEntity, storeLabel: String?): String =
        if (storeLabel != null && isImported(collection.id)) collection.name.removePrefix("$storeLabel: ") else collection.name

    /**
     * The collections Retro Games lists: only those with at least one Retro
     * member, so a store's collections (PC games only) never appear there.
     * [isRetro] says whether a library id is one of Retro's games.
     */
    fun retroCollections(membership: CollectionMembership, isRetro: (String) -> Boolean): List<CollectionEntity> =
        membership.collections.filter { collection -> membership.byCollection[collection.id].orEmpty().any(isRetro) }

    /** A collection opened from Retro lists its Retro members only, in the order they were added. */
    fun <T> retroMembers(collectionId: String, membership: CollectionMembership, retroById: Map<String, T>): List<T> =
        membership.byCollection[collectionId].orEmpty().mapNotNull { retroById[it] }

    /**
     * How many of a list's cards are in each collection, hidden cards left out
     * (docs/SPEC.md 7j, "Hidden is one rule"). One pass over [cards]; [ids]
     * gives a card's copies, so a merged card counts once.
     */
    fun counts(cards: List<LibraryEntry>, membership: CollectionMembership, ids: (LibraryEntry) -> Collection<String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (card in cards) {
            if (card.hidden) continue
            membership.collectionsOf(ids(card)).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts
    }

    /**
     * Whether a collection whose cards in a list are [members] (hidden ones
     * included) is shown there: not when every one of them is hidden.
     */
    fun shown(members: List<LibraryEntry>): Boolean = members.isEmpty() || members.any { !it.hidden }
}

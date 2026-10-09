package dev.droidtop.library

import dev.droidtop.library.consoles.CollectionEntity
import dev.droidtop.library.consoles.CollectionMemberEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the membership a list collects says, each time the tables change
 * (docs/SPEC.md 7i, "Collections"): from entry to collections, and a merged
 * card is a member if any of its copies is.
 */
class CollectionMembershipFlowTest {

    private val collections = listOf(CollectionEntity("a", "A"), CollectionEntity("b", "B"))

    @Test
    fun `each game maps to every collection it is in`() {
        val membership = CollectionMembership.of(
            collections,
            listOf(CollectionMemberEntity("a", "steam:1"), CollectionMemberEntity("b", "steam:1"), CollectionMemberEntity("b", "gog:9")),
        )
        assertEquals(setOf("a", "b"), membership.collectionsOf(listOf("steam:1")))
        assertEquals(listOf("steam:1", "gog:9"), membership.byCollection["b"])
        assertEquals(emptySet<String>(), membership.collectionsOf(listOf("epic:2")))
    }

    @Test
    fun `a merged card is a member if any copy is`() {
        val membership = CollectionMembership.of(collections, listOf(CollectionMemberEntity("a", "gog:9")))
        assertEquals(setOf("a"), membership.collectionsOf(listOf("steam:1", "gog:9")))
    }

    @Test
    fun `a game added to a collection counts in the next snapshot, with no read per game`() {
        val before = CollectionMembership.of(collections, listOf(CollectionMemberEntity("a", "steam:1")))
        val after = CollectionMembership.of(collections, listOf(CollectionMemberEntity("a", "steam:1"), CollectionMemberEntity("a", "steam:2")))
        val cards = listOf("steam:1", "steam:2").map { LibraryEntry(id = it, title = it, kind = LibraryEntryKind.WINE_PROFILE) }
        assertEquals(1, CollectionScope.counts(cards, before) { listOf(it.id) }["a"])
        assertEquals(2, CollectionScope.counts(cards, after) { listOf(it.id) }["a"])
    }
}

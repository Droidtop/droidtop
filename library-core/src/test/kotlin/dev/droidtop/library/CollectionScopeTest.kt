package dev.droidtop.library

import dev.droidtop.library.consoles.CollectionEntity
import dev.droidtop.library.consoles.CollectionMemberEntity
import dev.droidtop.library.stores.StoreCollections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a collection shows, one rule for Retro and PC Games (docs/SPEC.md 7g, "A store's collections"). */
class CollectionScopeTest {

    private val mine = CollectionEntity("c1", "Couch co-op")
    private val mixed = CollectionEntity("c2", "Mixed")
    private val steam = CollectionEntity(StoreCollections.prefixFor("steam") + "7", "Steam: Backlog")

    private val membership = CollectionMembership.of(
        listOf(mine, mixed, steam),
        listOf(
            CollectionMemberEntity("c1", "/roms/snes/a.sfc"),
            CollectionMemberEntity("c2", "/roms/gba/b.gba"),
            CollectionMemberEntity("c2", "steam:440"),
            CollectionMemberEntity(steam.id, "steam:440"),
            CollectionMemberEntity(steam.id, "steam:570"),
        ),
    )
    private val retro = setOf("/roms/snes/a.sfc", "/roms/gba/b.gba")

    @Test
    fun `Retro lists only collections with a Retro member, so a store's never appear`() {
        assertEquals(listOf(mine, mixed), CollectionScope.retroCollections(membership) { it in retro })
    }

    @Test
    fun `a mixed collection opened from Retro lists its Retro members only`() {
        val retroById = retro.associateWith { it }
        assertEquals(listOf("/roms/gba/b.gba"), CollectionScope.retroMembers("c2", membership, retroById))
        assertEquals(emptyList<String>(), CollectionScope.retroMembers(steam.id, membership, retroById))
    }

    @Test
    fun `an imported collection knows its store and drops the store's name from its own`() {
        assertTrue(CollectionScope.isImported(steam.id))
        assertFalse(CollectionScope.isImported(mine.id))
        assertEquals("steam", CollectionScope.importedFrom(steam.id))
        assertNull(CollectionScope.importedFrom(mine.id))
        assertEquals("Backlog", CollectionScope.shortName(steam, "Steam"))
        assertEquals("Couch co-op", CollectionScope.shortName(mine, "Steam"))
    }

    private fun card(id: String, hidden: Boolean = false) = LibraryEntry(id = id, title = id, kind = LibraryEntryKind.WINE_PROFILE, hidden = hidden)

    @Test
    fun `hidden games do not count, and a collection of hidden games only is not shown`() {
        val cards = listOf(card("steam:440"), card("steam:570", hidden = true))
        val counts = CollectionScope.counts(cards, membership) { listOf(it.id) }
        assertEquals(1, counts[steam.id])
        assertEquals(1, counts["c2"])
        assertTrue(CollectionScope.shown(cards))
        assertFalse(CollectionScope.shown(listOf(card("steam:570", hidden = true))))
    }
}

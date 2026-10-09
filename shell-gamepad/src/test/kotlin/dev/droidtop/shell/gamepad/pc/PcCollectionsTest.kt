package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.CollectionMembership
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.CollectionEntity
import dev.droidtop.library.consoles.CollectionMemberEntity
import dev.droidtop.library.stores.StoreCollections
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibraryQueryContext
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.NamedLibraryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Collections tab (docs/SPEC.md 7i, "Collections"): its groups, counts, marks and moves. */
class PcCollectionsTest {

    private val own = CollectionEntity("c1", "Couch co-op")
    private val hiddenOnly = CollectionEntity("c2", "Secret")
    private val imported = CollectionEntity(StoreCollections.prefixFor("steam") + "7", "${dev.droidtop.library.PcSource.Store("steam").label()}: Backlog")
    private val emptyImport = CollectionEntity(StoreCollections.prefixFor("steam") + "8", "Gone")

    private val membership = CollectionMembership.of(
        listOf(own, hiddenOnly, imported, emptyImport),
        listOf(
            CollectionMemberEntity("c1", "steam:1"),
            CollectionMemberEntity("c2", "steam:3"),
            CollectionMemberEntity(imported.id, "steam:1"),
            CollectionMemberEntity(imported.id, "steam:2"),
            CollectionMemberEntity(emptyImport.id, "steam:99"),
        ),
    )

    private fun card(id: String, hidden: Boolean = false) = LibraryEntry(id = id, title = id, kind = LibraryEntryKind.WINE_PROFILE, hidden = hidden)
    private val cards = listOf(card("steam:1"), card("steam:2"), card("steam:3", hidden = true))

    private val scope = LibraryQueryScope(
        id = "pc",
        facets = listOf(LibraryFacet.COLLECTION, LibraryFacet.HIDDEN),
        sorts = listOf(LibrarySortKey.NAME),
        context = LibraryQueryContext(collectionsOf = { membership.collectionsOf(listOf(it.id)) }),
    )

    @Test
    fun `own collections, then saved views, then each store's, hidden-only and empty imports left out`() {
        val saved = listOf(
            NamedLibraryView("Everything", LibraryQuery(), id = "v1", pinned = false),
            NamedLibraryView("Backlog", collectionQuery(imported.id), id = collectionViewId(imported.id), pinned = true),
        )
        val groups = collectionGroups(cards, membership, { listOf(it.id) }, saved, scope)

        assertEquals(listOf("Your collections", "Your saved views", "From ${dev.droidtop.library.PcSource.Store("steam").label()}"), groups.map { it.title })
        assertEquals(listOf("Couch co-op"), groups[0].tiles.map { it.name })
        // A pinned collection's own tab is the collection's tile, not a second saved view.
        assertEquals(listOf("Everything"), groups[1].tiles.map { it.name })
        assertEquals(2, groups[1].tiles.single().count)
        val backlog = groups[2].tiles.single()
        assertEquals("Backlog", backlog.name)
        assertEquals(2, backlog.count)
        assertTrue(backlog.pinned)
        assertEquals(setOf(imported.id), backlog.query.selected(LibraryFacet.COLLECTION))
    }

    @Test
    fun `a collection's tile opens the grid filtered to it`() {
        val shown = collectionQuery(imported.id).applyTo(cards, scope)
        assertEquals(listOf("steam:1", "steam:2"), shown.map { it.id })
    }

    @Test
    fun `the cursor moves by column within and across groups, and stops at the edges`() {
        val tile = { name: String -> CollectionTile(name, name, 0, false, LibraryQuery()) }
        val groups = listOf(
            CollectionGroup("A", listOf(tile("a0"), tile("a1"), tile("a2"))),
            CollectionGroup("B", listOf(tile("b0"))),
        )
        val rows = tileRows(groups, columns = 2)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), rows)
        assertEquals(2, tileStep(rows, 1, GamepadAction.DOWN))
        assertEquals(3, tileStep(rows, 2, GamepadAction.DOWN))
        assertEquals(0, tileStep(rows, 2, GamepadAction.UP))
        assertNull(tileStep(rows, 0, GamepadAction.UP))
        assertNull(tileStep(rows, 1, GamepadAction.RIGHT))
        assertEquals(1, tileStep(rows, 0, GamepadAction.RIGHT))
        // Headings take a grid slot each: the second group's first tile is the fifth item.
        assertEquals(5, gridIndexOfTile(groups, 3))
        assertEquals(1, gridIndexOfTile(groups, 0))
    }
}

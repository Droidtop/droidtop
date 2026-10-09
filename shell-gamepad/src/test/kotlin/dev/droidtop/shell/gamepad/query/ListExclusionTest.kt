package dev.droidtop.shell.gamepad.query

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.shell.gamepad.pc.freeRowText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule for which games a list shows (docs/SPEC.md 7j, "Hidden is one
 * rule", extended by Droidtop/tracker#397 slice B), row by row of its precedence.
 */
class ListExclusionTest {

    private fun row(
        holding: StoreHolding,
        installed: Boolean = false,
        played: Boolean = false,
        hidden: Boolean = false,
        id: String = "steam:${holding.name}:$installed:$played:$hidden",
    ) = LibraryEntry(
        id = id,
        title = id,
        kind = LibraryEntryKind.WINE_PROFILE,
        hidden = hidden,
        lastPlayedEpochMs = if (played) 1_000L else null,
        pcInfo = PcInfo(storeId = id, installed = installed, holding = holding),
    )

    private val defaults = OwnershipOptions()
    private val ownershipFree = LibraryQuery(facets = mapOf(LibraryFacet.OWNERSHIP.key to setOf(StoreHolding.FREE.name)))
    private val hiddenAsked = LibraryQuery(facets = mapOf(LibraryFacet.HIDDEN.key to setOf(HIDDEN_YES)))

    private data class Case(
        val name: String,
        val entry: LibraryEntry,
        val expected: Exclusion?,
        val place: ListPlace = ListPlace.LIST,
        val options: OwnershipOptions = OwnershipOptions(),
        val query: LibraryQuery? = null,
        val includeHidden: Boolean = false,
    )

    @Test
    fun `every precedence row`() {
        val cases = listOf(
            // 1. Hidden beats everything, unless asked for.
            Case("hidden owned", row(StoreHolding.OWNED, hidden = true), Exclusion.HIDDEN),
            Case("hidden, activity", row(StoreHolding.OWNED, installed = true, hidden = true), Exclusion.HIDDEN, place = ListPlace.ACTIVITY),
            Case("hidden, Hidden facet", row(StoreHolding.OWNED, hidden = true), null, query = hiddenAsked),
            Case("hidden, search switch", row(StoreHolding.OWNED, hidden = true), null, includeHidden = true),
            Case("hidden free, Ownership facet", row(StoreHolding.FREE, hidden = true), Exclusion.HIDDEN, query = ownershipFree),
            // 2. The Ownership facet chooses for itself.
            Case("free, Ownership = Free", row(StoreHolding.FREE), null, query = ownershipFree),
            Case("shared with option off, Ownership facet", row(StoreHolding.FAMILY), null, options = OwnershipOptions(showShared = false), query = ownershipFree),
            // 3. Installed, Continue playing and Recently played ignore ownership.
            Case("shared, option off, activity", row(StoreHolding.FAMILY, installed = true), null, place = ListPlace.ACTIVITY, options = OwnershipOptions(showShared = false)),
            // 4. The options.
            Case("owned", row(StoreHolding.OWNED), null),
            Case("not owned", row(StoreHolding.NOT_OWNED), null),
            Case("not owned, installed", row(StoreHolding.NOT_OWNED, installed = true), null),
            Case("shared, default", row(StoreHolding.FAMILY), null),
            Case("shared, option off", row(StoreHolding.FAMILY), Exclusion.SHARED, options = OwnershipOptions(showShared = false)),
            Case("free, default", row(StoreHolding.FREE), Exclusion.FREE),
            Case("free, option on", row(StoreHolding.FREE), null, options = OwnershipOptions(showFree = true)),
            // 5. A free game installed or played is in the library.
            Case("free, installed", row(StoreHolding.FREE, installed = true), null),
            Case("free, played, not installed", row(StoreHolding.FREE, played = true), null),
            Case("free, uninstalled, never played", row(StoreHolding.FREE), Exclusion.FREE),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                listExclusion(case.entry, case.place, case.options, case.query, case.includeHidden),
            )
        }
        // A list with no ownership options (a console list) has no ownership rule.
        assertEquals(null, listExclusion(row(StoreHolding.FREE), ListPlace.LIST, options = null))
        assertEquals(defaults, OwnershipOptions(showShared = true, showFree = false))
    }

    private val scope = LibraryQueryScope(
        id = "pc",
        facets = listOf(LibraryFacet.SOURCE, LibraryFacet.OWNERSHIP, LibraryFacet.INSTALLED, LibraryFacet.HIDDEN),
        sorts = listOf(LibrarySortKey.NAME),
        ownership = OwnershipOptions(),
    )

    @Test
    fun `one row per app at its strongest holding counts once, and the Ownership counts add up`() {
        assertEquals(StoreHolding.OWNED, StoreHolding.strongest(listOf(StoreHolding.FAMILY, StoreHolding.OWNED)))
        assertEquals(StoreHolding.FAMILY, StoreHolding.strongest(listOf(StoreHolding.NOT_OWNED, StoreHolding.FREE, StoreHolding.FAMILY)))
        val rows = listOf(
            row(StoreHolding.OWNED, id = "steam:1"), row(StoreHolding.OWNED, id = "steam:2"),
            row(StoreHolding.FAMILY, id = "steam:3"),
            row(StoreHolding.FREE, id = "steam:4"), row(StoreHolding.FREE, id = "steam:5"), row(StoreHolding.FREE, id = "steam:6", installed = true),
            row(StoreHolding.NOT_OWNED, id = "steam:7", installed = true),
        )
        val offer = LibraryQuery().facetOffers(rows, scope).first { it.facet == LibraryFacet.OWNERSHIP }
        assertEquals(listOf("OWNED" to 2, "FAMILY" to 1, "FREE" to 3, "NOT_OWNED" to 1), offer.values.map { it.value to it.count })
        assertEquals(rows.size, offer.values.sumOf { it.count })
        assertEquals("Free to play (not in your library)", LibraryFacet.OWNERSHIP.valueLabel("FREE"))
        // All games: owned, shared, the installed free game and the one no longer owned; two free rows held back.
        assertEquals(5, LibraryQuery().applyTo(rows, scope).size)
        assertEquals(5, LibraryQuery().totalIn(rows, scope))
        assertEquals(2, LibraryQuery().freeNotInLibrary(rows, scope))
        // The List option shows them, and the footer row says they are shown.
        val showing = scope.copy(ownership = OwnershipOptions(showFree = true))
        assertEquals(7, LibraryQuery().applyTo(rows, showing).size)
        assertEquals(2, LibraryQuery().freeNotInLibrary(rows, showing))
        // Ownership = Free shows exactly the free rows, whatever the options.
        val free = LibraryQuery(facets = mapOf(LibraryFacet.OWNERSHIP.key to setOf("FREE")))
        assertEquals(3, free.applyTo(rows, scope).size)
        assertEquals(0, free.freeNotInLibrary(rows, scope))
        // The Installed view ignores ownership and holds no row to explain.
        val installed = LibraryQuery(facets = mapOf(LibraryFacet.INSTALLED.key to setOf(INSTALLED_YES)))
        assertEquals(0, installed.freeNotInLibrary(rows, scope))
    }

    @Test
    fun `the footer row says what is held back and how to change it`() {
        assertEquals("1,506 free-to-play games not in your library are not shown. Show them", freeRowText(1506, showing = false))
        assertEquals("Showing 1,506 free-to-play games not in your library. Hide them", freeRowText(1506, showing = true))
        assertEquals("1 free-to-play game not in your library is not shown. Show them", freeRowText(1, showing = false))
    }
}

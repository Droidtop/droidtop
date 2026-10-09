package dev.droidtop.shell.gamepad.pc

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.PcSource
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQueryContext
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.LibraryViewPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every store is treated alike by the PC surface (docs/SPEC.md 7g "Stores",
 * 7j "Filters", Droidtop/tracker#397 slice A): the same checks, run for every
 * store in the registry, built in or plugged in. A store that needed a line of
 * its own anywhere in the PC surface would fail here.
 */
class StoreNeutralityTest {

    private class FakeStore(override val id: String, override val label: String) : StoreLibrary {
        override fun signedIn(context: Context) = true
        override val signInKind = StoreSignInKind.API_KEY
        override fun signIn(context: Context): StoreSignIn = StoreSignIn.ApiKey("https://example.invalid/keys")
        override suspend fun completeSignIn(context: Context, secret: String) = Result.success<String?>(null)
        override suspend fun signOut(context: Context) = Result.success(Unit)
        override suspend fun sync(context: Context) = Result.success(0)
        override suspend fun games(context: Context) = emptyList<StoreGame>()
        override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress) = "Installed"
        override suspend fun uninstall(context: Context, gameId: String) = Result.success(Unit)
        override fun changeStamp(context: Context) = 1L
    }

    init {
        // The built-in stores' ids and names, and two a plugin could bring.
        listOf(
            FakeStore("steam", "Steam"), FakeStore("gog", "GOG"), FakeStore("epic", "Epic"),
            FakeStore("amazon", "Amazon"), FakeStore("itch", "itch.io"),
            FakeStore("battlenet", "Battle.net"), FakeStore("pluginstore", "Some Plugin Store"),
        ).forEach(StoreLibraries::register)
    }

    private val scope = LibraryQueryScope(
        id = "pc",
        facets = listOf(LibraryFacet.SOURCE, LibraryFacet.INSTALLED, LibraryFacet.HIDDEN),
        sorts = listOf(LibrarySortKey.NAME),
        context = LibraryQueryContext(pcRoots = listOf("/storage/card/Games")),
    )

    private fun row(store: StoreLibrary, n: Int) = LibraryEntry(
        id = "${store.id}:$n",
        title = "${store.label} game $n",
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = "${store.id}:$n", installed = n % 2 == 0),
    )

    @Test
    fun `every store's rows are its Source value, named by the store itself`() {
        for (store in StoreLibraries.all()) {
            val rows = (1..3).map { row(store, it) }
            rows.forEach { assertEquals(store.id, LibraryFacet.SOURCE.valuesOf(it, scope.context).single()) }
            assertEquals(store.label, LibraryFacet.SOURCE.valueLabel(store.id))
            assertEquals(store.label, PcSource.of(rows[0])?.label())
        }
    }

    @Test
    fun `every store filters, counts and opens the same way`() {
        val all = StoreLibraries.all().flatMap { store -> (1..2).map { row(store, it) } }
        for (store in StoreLibraries.all()) {
            val state = PcGamesState().apply { showSource(store.id) }
            assertEquals(setOf(store.id), state.query.selected(LibraryFacet.SOURCE))
            assertEquals(2, state.query.applyTo(all, scope).size)
            assertTrue(state.query.applyTo(all, scope).all { it.id.startsWith("${store.id}:") })
            val offer = state.query.facetOffers(all, scope).first { it.facet == LibraryFacet.SOURCE }
            assertEquals(2, offer.values.first { it.value == store.id }.count)
        }
    }

    @Test
    fun `the Source values follow the registry's order, never a list of names`() {
        val all = StoreLibraries.all().reversed().map { row(it, 1) }
        val offer = dev.droidtop.shell.gamepad.query.LibraryQuery().facetOffers(all, scope).first { it.facet == LibraryFacet.SOURCE }
        assertEquals(StoreLibraries.all().map { it.id }, offer.values.map { it.value })
    }

    @Test
    fun `a capsule and the focus line name every store the same way`() {
        for (store in StoreLibraries.all()) {
            val game = row(store, 2)
            assertEquals(store.label, kindBadgeOf(game, emptyMap(), origins = 2)?.detail)
            assertTrue(focusLine(game, null, 1).contains(store.label))
        }
    }

    @Test
    fun `a view saved before ids filters the same store after the update`() {
        for (store in StoreLibraries.all()) {
            val old = dev.droidtop.shell.gamepad.query.LibraryQuery(facets = mapOf(LibraryViewPrefs.LEGACY_STORE_KEY to setOf(store.label)))
            val migrated = LibraryViewPrefs.migrateLegacyStore(old, { label -> StoreLibraries.all().firstOrNull { it.label == label }?.id }, emptyList())
            assertEquals(setOf(store.id), migrated.selected(LibraryFacet.SOURCE))
            assertTrue(LibraryViewPrefs.LEGACY_STORE_KEY !in migrated.facets)
        }
    }
}

package dev.droidtop.shell.gamepad.query

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one search/filter/sort pass every library list shares (docs/SPEC.md
 * 7i): values OR within a facet, facets AND, the search text on top, and
 * the sorts that have data behind them -- never a sort on a fact the
 * model does not carry.
 */
class LibraryQueryTest {

    private fun game(
        id: String,
        title: String = id,
        favorite: Boolean = false,
        hidden: Boolean = false,
        pcInfo: PcInfo? = null,
        playtimeSeconds: Long = 0,
        lastPlayedEpochMs: Long? = null,
        releaseDate: String? = null,
        rating: Float? = null,
        genre: String? = null,
        developer: String? = null,
        artworkUri: String? = null,
        availableUpdate: String? = null,
    ) = LibraryEntry(
        id = id,
        title = title,
        kind = LibraryEntryKind.WINE_PROFILE,
        favorite = favorite,
        hidden = hidden,
        pcInfo = pcInfo,
        playtimeSeconds = playtimeSeconds,
        lastPlayedEpochMs = lastPlayedEpochMs,
        releaseDate = releaseDate,
        rating = rating,
        genre = genre,
        developer = developer,
        artworkUri = artworkUri,
        availableUpdate = availableUpdate,
    )

    private val scope = LibraryQueryScope(
        id = "test",
        facets = LibraryFacet.entries.toList(),
        sorts = LibrarySortKey.entries.toList(),
        context = LibraryQueryContext(now = { 1_000_000_000L }),
    )

    private val steamInstalled = game(
        "steam:1", "Half", pcInfo = PcInfo(source = "Steam", installed = true, sizeBytes = 20),
        playtimeSeconds = 300, lastPlayedEpochMs = 999_999_000, rating = 0.8f,
        releaseDate = "20041116T000000", genre = "Shooter", developer = "Valve",
        artworkUri = "file:///half.png",
    )
    private val gogNotInstalled = game(
        "gog:2", "Wiedzm", pcInfo = PcInfo(source = "GOG", installed = false, sizeBytes = 30),
        favorite = true,
    )
    private val folder = game("/games/folder/Game Three", pcInfo = null, hidden = true)

    @Test
    fun `values within one facet are or`() {
        val query = LibraryQuery(facets = mapOf(LibraryFacet.STORE.key to setOf("Steam", "GOG")))

        val shown = query.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope)

        assertEquals(listOf("Half", "Wiedzm"), shown.map { it.title })
    }

    @Test
    fun `facets are and`() {
        val query = LibraryQuery(facets = mapOf(LibraryFacet.STORE.key to setOf("Steam"), LibraryFacet.INSTALLED.key to setOf("Installed")))

        val shown = query.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope)

        assertEquals(listOf("Half"), shown.map { it.title })
    }

    @Test
    fun `an entry the facet does not apply to is excluded while it filters`() {
        // A folder game has no install state, so "Installed" cannot match
        // it -- a filter that cannot match is not a filter a game
        // silently passes.
        val query = LibraryQuery(facets = mapOf(LibraryFacet.INSTALLED.key to setOf("Not installed")))

        val shown = query.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope)

        assertEquals(listOf("Wiedzm"), shown.map { it.title })
    }

    @Test
    fun `a facet with nothing selected stops filtering`() {
        val selected = LibraryQuery(facets = mapOf(LibraryFacet.STORE.key to setOf("Steam")))
            .withToggled(LibraryFacet.STORE, "Steam", on = false)

        assertTrue(selected.facets.isEmpty())
        assertEquals(3, selected.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope).size)
    }

    @Test
    fun `the search text matches the title, genre and developer, case-insensitively`() {
        val query = LibraryQuery(text = "valve")

        val byDeveloper = query.applyTo(listOf(steamInstalled), scope)
        val byGenre = LibraryQuery(text = "shooter").applyTo(listOf(steamInstalled), scope)
        val byTitle = LibraryQuery(text = "HALF").applyTo(listOf(steamInstalled), scope)

        assertEquals(listOf("Half"), byDeveloper.map { it.title })
        assertEquals(listOf("Half"), byGenre.map { it.title })
        assertEquals(listOf("Half"), byTitle.map { it.title })
        assertTrue(LibraryQuery(text = "nothing called this").matches(steamInstalled, scope).not())
    }

    @Test
    fun `recently played means the last fourteen days`() {
        val fresh = game("a", lastPlayedEpochMs = 1_000_000_000 - 1)
        val stale = game("b", lastPlayedEpochMs = 1_000_000_000 - RECENT_WINDOW_MS - 1)
        val never = game("c")
        val query = LibraryQuery(facets = mapOf(LibraryFacet.RECENTLY_PLAYED.key to setOf(RECENT_YES)))

        val shown = query.applyTo(listOf(fresh, stale, never), scope)

        assertEquals(listOf("a"), shown.map { it.title })
    }

    @Test
    fun `the year sort is oldest first with unknown after every dated game`() {
        val dated = listOf(game("newer", releaseDate = "20100101T000000"), game("older", releaseDate = "19990101T000000"))
        val undated = game("undated")

        val shown = LibraryQuery(sort = LibrarySortKey.YEAR).applyTo(dated + undated, scope)

        assertEquals(listOf("older", "newer", "undated"), shown.map { it.title })
    }

    @Test
    fun `playtime, rating and size sorts read only real facts`() {
        val big = game("big", pcInfo = PcInfo(source = "Steam", installed = true, sizeBytes = 30), playtimeSeconds = 10, rating = 0.5f)
        val small = game("small", pcInfo = PcInfo(source = "Steam", installed = true, sizeBytes = 10), playtimeSeconds = 90, rating = 0.9f)

        assertEquals(
            listOf("small", "big"),
            LibraryQuery(sort = LibrarySortKey.PLAYTIME).applyTo(listOf(big, small), scope).map { it.title },
        )
        assertEquals(
            listOf("small", "big"),
            LibraryQuery(sort = LibrarySortKey.RATING).applyTo(listOf(big, small), scope).map { it.title },
        )
        assertEquals(
            listOf("big", "small"),
            LibraryQuery(sort = LibrarySortKey.SIZE).applyTo(listOf(big, small), scope).map { it.title },
        )
    }

    @Test
    fun `ready and runner facets read what the scope knows, and say nothing when it does not`() {
        val ready = game("ready")
        val setup = game("setup")
        val knows = scope.copy(
            context = LibraryQueryContext(
                runnerReadyOf = { if (it.title == "ready") true else if (it.title == "setup") false else null },
                runnerLabelOf = { if (it.title == "ready") "enginehost (Ren'Py)" else null },
                now = { 1_000_000_000L },
            ),
        )

        val readyQuery = LibraryQuery(facets = mapOf(LibraryFacet.READY.key to setOf(READY_YES)))
        val setupQuery = LibraryQuery(facets = mapOf(LibraryFacet.READY.key to setOf(READY_NO)))
        val runnerQuery = LibraryQuery(facets = mapOf(LibraryFacet.RUNNER.key to setOf("enginehost (Ren'Py)")))

        assertEquals(listOf("ready"), readyQuery.applyTo(listOf(ready, setup), knows).map { it.title })
        assertEquals(listOf("setup"), setupQuery.applyTo(listOf(ready, setup), knows).map { it.title })
        assertEquals(listOf("ready"), runnerQuery.applyTo(listOf(ready, setup), knows).map { it.title })
    }

    @Test
    fun `update, artwork, hidden and favourites facets read the entry's own flags`() {
        // Every entry but `artless` carries real art -- the MISSING_ART
        // check needs at least one entry WITH artworkUri to actually be a
        // test: `game()` defaults artworkUri to null, so leaving these
        // three at the default made every entry in `base` "missing art"
        // and the assertion below passed only by accident of list order
        // (real bug this fixes, caught live: CI's own testDebugUnitTest,
        // 2026-09-28).
        val updating = game("updating", availableUpdate = "1.1", artworkUri = "art://updating")
        val artless = game("artless", artworkUri = null)
        val hidden = game("hidden", hidden = true, artworkUri = "art://hidden")
        val favourite = game("favourite", favorite = true, artworkUri = "art://favourite")

        val base = listOf(updating, artless, hidden, favourite)
        assertEquals(
            listOf("updating"),
            LibraryQuery(facets = mapOf(LibraryFacet.UPDATE.key to setOf(UPDATE_YES))).applyTo(base, scope).map { it.title },
        )
        assertEquals(
            listOf("artless"),
            LibraryQuery(facets = mapOf(LibraryFacet.MISSING_ART.key to setOf(MISSING_ART_YES))).applyTo(base, scope).map { it.title },
        )
        assertEquals(
            listOf("hidden"),
            LibraryQuery(facets = mapOf(LibraryFacet.HIDDEN.key to setOf(HIDDEN_YES))).applyTo(base, scope).map { it.title },
        )
        assertEquals(
            listOf("favourite"),
            LibraryQuery(facets = mapOf(LibraryFacet.FAVOURITES.key to setOf(FAVOURITES_YES))).applyTo(base, scope).map { it.title },
        )
    }

    @Test
    fun `a query and its saved views survive the store's round trip`() {
        val query = LibraryQuery(
            text = "witcher",
            facets = mapOf(LibraryFacet.STORE.key to setOf("GOG", "Steam"), LibraryFacet.INSTALLED.key to setOf(INSTALLED_YES)),
            sort = LibrarySortKey.PLAYTIME,
        )

        assertEquals(query, LibraryViewPrefs.decodeQuery(LibraryViewPrefs.encodeQuery(query)))
    }

    @Test
    fun `a stored query this build cannot read is the default view, never an error`() {
        assertNull(LibraryViewPrefs.decodeQuery(null))
        assertEquals(LibraryQuery(), LibraryViewPrefs.decodeQuery("not json at all"))
        assertEquals(LibraryQuery(), LibraryViewPrefs.decodeQuery("""{"sort":"NO_LONGER_A_SORT"}"""))
    }

    @Test
    fun `saved views keep their order and a re-saved name replaces its view`() {
        val first = NamedLibraryView("Mine", LibraryQuery(sort = LibrarySortKey.RATING))
        val second = NamedLibraryView("Yours", LibraryQuery(text = "x"))
        val raw = LibraryViewPrefs.encodeViews(listOf(first, second))

        assertEquals(listOf(first, second), LibraryViewPrefs.decodeViews(raw))

        val replaced = first.copy(query = LibraryQuery(sort = LibrarySortKey.SIZE))
        val reEncoded = LibraryViewPrefs.encodeViews(listOf(second, replaced))

        assertEquals(listOf(second, replaced), LibraryViewPrefs.decodeViews(reEncoded))
    }

    @Test
    fun `the year facet reads only a real four-digit year`() {
        assertEquals("2004", LibraryFacet.YEAR.valuesOf(steamInstalled, scope.context).single())
        assertTrue(LibraryFacet.YEAR.valuesOf(gogNotInstalled, scope.context).isEmpty())
        assertFalse(LibraryFacet.YEAR.valuesOf(game("x", releaseDate = "unknown"), scope.context).isNotEmpty())
    }
}

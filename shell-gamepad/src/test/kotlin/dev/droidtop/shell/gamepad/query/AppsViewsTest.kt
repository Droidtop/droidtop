package dev.droidtop.shell.gamepad.query

import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.InstalledAppFacts
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Apps strip's views over the Category and Recently used facets (docs/SPEC.md 7j). */
class AppsViewsTest {
    private val now = 100_000_000_000L

    private fun app(id: String, flaggedGame: Boolean = false, system: Boolean = false, hidden: Boolean = false) = LibraryEntry(
        id = id,
        title = id,
        kind = LibraryEntryKind.NATIVE_ANDROID_APP,
        hidden = hidden,
        appFacts = InstalledAppFacts(firstInstalledEpochMs = 1L, flaggedGame = flaggedGame, system = system),
    )

    private val apps = listOf(
        app("game", flaggedGame = true),
        app("emu"),
        app("settings", system = true),
        app("notes"),
        app("secret", hidden = true),
    )
    private val scope = appsQueryScope(
        AppCategoryRules(emulatorPackages = setOf("emu")),
        usage = mapOf("notes" to now - 1_000L),
        running = emptySet(),
    ).let { it.copy(context = it.context.copy(now = { now })) }

    @Test
    fun `each view counts what its own filter shows and hidden apps are in none`() {
        val views = appsStripViews(emptyList())
        val counts = appsViewCounts(apps, scope, views)

        assertEquals(listOf("All", "Games", "Emulators", "Tools", "Recently used"), views.map { it.name })
        assertEquals(mapOf("All" to 4, "Games" to 1, "Emulators" to 1, "Tools" to 2, "Recently used" to 1), counts)
        assertEquals("Tools · 2", appsStripLabel(views[3], counts))
    }

    @Test
    fun `the lit view follows the filters and not the sort`() {
        val views = appsStripViews(emptyList())

        assertEquals(0, appsActiveView(views, LibraryQuery()))
        assertEquals(0, appsActiveView(views, LibraryQuery(sort = LibrarySortKey.MOST_USED, reversed = true)))
        assertEquals(1, appsActiveView(views, views[1].query))
        val extra = views[1].query.withToggled(LibraryFacet.RUNNING, RUNNING_YES, true)
        assertEquals(-1, appsActiveView(views, extra))
    }

    @Test
    fun `a built-in view keeps the chosen sort and a saved view brings its own`() {
        val sorted = LibraryQuery(sort = LibrarySortKey.ADDED, reversed = true)
        val games = appsStripViews(emptyList())[1]
        val picked = appsViewQuery(games, sorted)

        assertEquals(games.query.facets, picked.facets)
        assertEquals(LibrarySortKey.ADDED, picked.sort)
        val saved = NamedLibraryView("Mine", LibraryQuery(sort = LibrarySortKey.MOST_USED))
        assertEquals(saved.query, appsViewQuery(saved, sorted))
        // A saved view cannot take a built-in view's name.
        assertEquals(5, appsStripViews(listOf(NamedLibraryView("Games", LibraryQuery()))).size)
        assertEquals(6, appsStripViews(listOf(saved)).size)
    }

    @Test
    fun `the pill names the filters no view stands for`() {
        val query = LibraryQuery().withToggled(LibraryFacet.RUNNING, RUNNING_YES, true)

        assertEquals("Running, 2 of 4", query.pillText(scope, shown = 2, total = 4))
        assertNull(LibraryQuery().pillText(scope, shown = 4, total = 4))
    }
}

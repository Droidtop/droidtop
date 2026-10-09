package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.GameLinks
import dev.droidtop.library.SourceAnswer
import dev.droidtop.library.SourceLink
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "About this game" helpers the PC game page draws (PcGamePage.kt;
 * docs/SPEC.md 7h, 7i): the facts a scrape leaves, the ES-DE date
 * format, and the one line saying where each field came from. Pure
 * functions over in-memory [LibraryEntry] data, so the wording a player
 * reads is pinned here rather than only on hardware.
 */
class PcPageAboutTest {
    private fun entry(
        description: String? = null,
        developer: String? = null,
        publisher: String? = null,
        releaseDate: String? = null,
        genre: String? = null,
        series: String? = null,
        rating: Float? = null,
        fieldSources: Map<String, String> = emptyMap(),
    ) = LibraryEntry(
        id = "steam:1",
        title = "A Game",
        kind = LibraryEntryKind.WINE_PROFILE,
        description = description,
        developer = developer,
        publisher = publisher,
        releaseDate = releaseDate,
        genre = genre,
        series = series,
        rating = rating,
        fieldSources = fieldSources,
    )

    @Test
    fun aboutFactsListOnlyWhatWasScrapedInStorePageOrder() {
        val facts = aboutFacts(
            entry(
                developer = "Supergiant",
                publisher = "Devolver",
                releaseDate = "20240312T000000",
                genre = "Action",
                series = "Hollow",
                rating = 0.88f,
            ),
        )
        assertEquals(listOf("Developer", "Publisher", "Released", "Genre", "Series", "Rating"), facts.map { it.first })
        assertEquals("Supergiant", facts[0].second)
        assertEquals("Devolver", facts[1].second)
        // The date a person reads, not ES-DE's raw YYYYMMDDT000000.
        assertTrue(facts[2].second.isNotBlank())
        assertFalse(facts[2].second.contains("T000000"))
        assertEquals("Action", facts[3].second)
        assertEquals("Hollow", facts[4].second)
        // ES-DE's 0-1 rating on the five-star scale ES-DE draws it on.
        assertEquals("4.4 / 5", facts[5].second)
    }

    @Test
    fun aboutFactsDropAPartialDateRatherThanWidenIt() {
        assertEquals(emptyList<Pair<String, String>>(), aboutFacts(entry()))
        // A year alone is not a full date; nothing widens it into a day.
        assertTrue(aboutFacts(entry(releaseDate = "2024")).none { it.first == "Released" })
    }

    @Test
    fun formatReleaseDateReadsAFullEsDeDateAndRefusesTheRest() {
        val full = formatReleaseDate("20240312T000000")
        assertTrue(full != null && !full.contains("T000000"))
        assertNull(formatReleaseDate("2024"))
        assertNull(formatReleaseDate("not a date"))
    }

    @Test
    fun sourcesLineNamesEachSourceOnceWithItsFields() {
        assertNull(sourcesLine(entry()))
        assertEquals(
            "Description from IGDB. Cover and hero art from SteamGridDB.",
            sourcesLine(
                entry(
                    fieldSources = mapOf(
                        "description" to "IGDB",
                        "cover" to "SteamGridDB",
                        "hero" to "SteamGridDB",
                    ),
                ),
            ),
        )
    }

    @Test
    fun sourcesLineListsFieldsInFieldSourcesLabelOrderWhateverTheMapOrder() {
        assertEquals(
            "Description, developer and genre from IGDB. Cover from SteamGridDB.",
            sourcesLine(
                entry(
                    fieldSources = mapOf(
                        "cover" to "SteamGridDB",
                        "genre" to "IGDB",
                        "developer" to "IGDB",
                        "description" to "IGDB",
                    ),
                ),
            ),
        )
    }

    @Test
    fun sourcesLineSaysEditedByYouForTheMetadataEditor() {
        assertEquals(
            "Rating edited by you.",
            sourcesLine(entry(fieldSources = mapOf("rating" to "you"))),
        )
    }

    // --- the page's tabs, facts strip and parts (docs/SPEC.md 7i, "The game page") ---

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun everyFactRowLivesUnderExactlyOneTabAndPartsLeadOverview() {
        val rows = listOf("Times played", "Install location", "Version", "Latest", "Update", "Runner", "Manual", "Engine", "Developer", "About")
            .map { PageFact(it) } + PageFact("Official site", tab = PageTab.EXTRAS)
        val parts = listOf(PageFact("Parts"), PageFact("Week 1"))
        val byTab = groupRowsByTab(rows, parts)

        assertEquals(PageTab.values().toList(), byTab.keys.toList())
        assertEquals(rows.size + parts.size, byTab.values.sumOf { it.size })
        assertEquals(listOf("Parts", "Week 1", "Times played", "About"), byTab.getValue(PageTab.OVERVIEW).map { it.title })
        // An available update leads the tab.
        assertEquals(listOf("Update", "Version", "Latest"), byTab.getValue(PageTab.VERSIONS).map { it.title })
        assertEquals(listOf("Manual", "Official site"), byTab.getValue(PageTab.EXTRAS).map { it.title })
        // What no tab names is a detail, so a new row is never lost.
        assertEquals(listOf("Install location", "Runner", "Engine", "Developer"), byTab.getValue(PageTab.DETAILS).map { it.title })
        assertEquals(PageTab.DETAILS, pageTabOf("Something new"))
    }

    @Test
    fun theSourceRowsLiveUnderVersionsAndOpenSourcePageNeedsAKnownPage() {
        val source = UpdateSources.Source("forum", "Forum thread", null)
        assertEquals(PageTab.VERSIONS, pageTabOf(OPEN_SOURCE_ROW))
        val unlinked = sourceRows(listOf(source), null, {}, {})
        assertEquals(listOf("Forum thread"), unlinked.map { it.title })
        assertEquals(PageTab.VERSIONS, unlinked[0].tab)
        assertEquals("Link", unlinked[0].value)
        // Linked, but no page known yet: nothing to open.
        val noPage = GameLinks(sources = listOf(SourceLink("forum", "123")))
        assertEquals(listOf("Forum thread"), sourceRows(listOf(source), noPage, {}, {}).map { it.title })
        val url = "https://www.example.org/threads/game.123/"
        val links = GameLinks(sources = listOf(SourceLink("forum", "123", SourceAnswer("1.0", 0L, false, url))))
        var opened: String? = null
        val linked = sourceRows(listOf(source), links, {}, { opened = it })
        assertEquals(listOf("Forum thread", OPEN_SOURCE_ROW), linked.map { it.title })
        assertEquals("#123", linked[0].value)
        // Only the host is shown; the whole link is the tip and what opens.
        assertEquals("example.org", linked[1].value)
        assertEquals(url, linked[1].tip)
        linked[1].onActivate?.invoke()
        assertEquals(url, opened)
        // No source installed: no rows, nothing to link to.
        assertEquals(emptyList<PageFact>(), sourceRows(emptyList(), links, {}, {}))
    }

    @Test
    fun aCheckSaysUpToDateOrWhatIsAvailableInOneLine() {
        fun link(version: String?, gone: Boolean = false) =
            SourceLink("forum", "1", SourceAnswer(version, 0L, gone))
        assertEquals("Up to date", checkOutcomeLine(null, link("v0.9.5"), listOf("0.9.5"), null))
        assertEquals("v0.9.6 is available", checkOutcomeLine(null, link("v0.9.6"), listOf("0.9.5"), null))
        assertEquals("Gone: private, moved or deleted", checkOutcomeLine(null, link(null, gone = true), listOf("0.9.5"), null))
        assertEquals("The source gives no version", checkOutcomeLine(null, link(null), listOf("0.9.5"), null))
        assertEquals("Newest is v1.0", checkOutcomeLine(null, link("v1.0"), listOf(""), null))
        assertEquals("offline", checkOutcomeLine("offline", link("v1.0"), listOf("1.0"), null))
    }

    @Test
    fun theVersionFactSaysInstalledAgainstLatest() {
        assertEquals("0.9.5", versionFact("0.9.5", null))
        assertEquals("0.9.5, v0.9.6 is available", versionFact("0.9.5", "0.9.6"))
        assertEquals("v0.9.6 is available", versionFact(null, "0.9.6"))
        assertNull(versionFact(null, null))
    }

    @Test
    fun installedVersionsComeFromTheFolderNamesOwnFirst() {
        val here = entry().copy(id = "/g/SomeGame-0.9.5-pc")
        val other = entry().copy(id = "/g/SomeGame-0.9.4-pc")
        assertEquals(listOf("0.9.5", "0.9.4"), installedVersions(here, listOf(here, other)))
        // A store row has no folder name to read a version from.
        assertEquals(emptyList<String>(), installedVersions(entry(), listOf(entry())))
    }

    @Test
    fun lastPlayedReadsAsAPersonSaysIt() {
        val now = 100 * day
        assertEquals("today", lastPlayedPhrase(now, now - 3_600_000))
        assertEquals("yesterday", lastPlayedPhrase(now, now - day - 1000))
        assertEquals("5 days ago", lastPlayedPhrase(now, now - 5 * day - 1000))
        assertEquals("3 weeks ago", lastPlayedPhrase(now, now - 21 * day - 1000))
        assertEquals("4 months ago", lastPlayedPhrase(now, now - 125 * day))
        assertEquals("over a year ago", lastPlayedPhrase(now, now - 400 * day))
        // A clock that ran backwards is today, never negative days.
        assertEquals("today", lastPlayedPhrase(now, now + day))
    }

    @Test
    fun playTimeIsShortInTheStripAndTheHeroCaption() {
        assertEquals("Not played yet", playtimeShort(0))
        assertEquals("Under a minute", playtimeShort(30))
        assertEquals("45 min", playtimeShort(45 * 60))
        assertEquals("2 h", playtimeShort(2 * 3600))
        assertEquals("2 h 5 min", playtimeShort(2 * 3600 + 5 * 60))

        val now = 100 * day
        assertEquals("Played yesterday \u00B7 2 h 5 min", heroCaption(entry().copy(lastPlayedEpochMs = now - day - 1000, playtimeSeconds = 2 * 3600 + 5 * 60), now))
        assertEquals("Not played yet", heroCaption(entry(), now))
    }

    @Test
    fun theFactsStripListsOnlyWhatExists() {
        val now = 100 * day
        val played = entry().copy(
            lastPlayedEpochMs = now - 2 * day - 1000,
            playtimeSeconds = 3600,
            availableUpdate = "0.9.6",
        )
        val strip = factsStrip(played, now, folderSizeBytes = 2048, installedVersion = "0.9.5", runsWith = "Wine", formatSize = { "$it B" })
        assertEquals(
            listOf(
                "Last played" to "2 days ago",
                "Play time" to "1 h",
                "Version" to "0.9.5, v0.9.6 is available",
                "Size" to "2048 B",
                "Runs with" to "Wine",
            ),
            strip,
        )

        val bare = factsStrip(entry(), now, folderSizeBytes = null, installedVersion = null, runsWith = null, formatSize = { "$it B" })
        assertEquals(listOf("Last played" to "Never", "Play time" to "Never played"), bare)
    }

    @Test
    fun aGameOfSeveralPartsListsThemAndAGameOfOneDoesNot() {
        val weeks = listOf("Week 1", "Week 2").map { week ->
            entry().copy(id = "/games/Some Game/$week", title = "Some Game - $week")
        }
        val facts = partFacts(weeks)
        assertEquals(listOf("Parts", "Week 1", "Week 2"), facts.map { it.title })
        assertEquals("2 parts", facts.first().value)

        assertEquals(emptyList<PageFact>(), partFacts(listOf(entry().copy(id = "/games/Single Game"))))
        assertEquals(emptyList<PageFact>(), partFacts(listOf(entry())))
    }

    @Test
    fun theActionHintNamesTheButtonUnderTheCursor() {
        assertEquals("Play", pageActionLabel(0, "Play"))
        assertEquals("Install", pageActionLabel(0, "Install"))
        assertEquals("Favourite", pageActionLabel(1, "Play"))
        assertEquals("Options", pageActionLabel(2, "Play"))
    }
}

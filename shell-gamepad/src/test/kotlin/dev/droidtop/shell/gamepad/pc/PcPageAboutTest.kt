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
        val o = PageTab.OVERVIEW
        val v = PageTab.VERSIONS
        val d = PageTab.DETAILS
        val rows = listOf(
            "Times played" to o, "Install location" to d, "Version" to v, "Latest" to v, "Update" to v, "Runner" to d,
            "Manual" to PageTab.EXTRAS, "Engine" to d, "Developer" to d, "About" to o,
        ).map { (title, tab) -> PageFact(title, tab = tab) } + PageFact("Official site", tab = PageTab.EXTRAS)
        val parts = listOf(PageFact("Parts", tab = o), PageFact("Week 1", tab = o))
        val byTab = groupRowsByTab(rows, parts)

        assertEquals(PageTab.values().toList(), byTab.keys.toList())
        assertEquals(rows.size + parts.size, byTab.values.sumOf { it.size })
        assertEquals(listOf("Parts", "Week 1", "Times played", "About"), byTab.getValue(PageTab.OVERVIEW).map { it.title })
        // An available update leads the tab.
        assertEquals(listOf("Update", "Version", "Latest"), byTab.getValue(PageTab.VERSIONS).map { it.title })
        assertEquals(listOf("Manual", "Official site"), byTab.getValue(PageTab.EXTRAS).map { it.title })
        // Details in order of use; the install location is one of More's technical rows, last.
        assertEquals(listOf("Runner", "Engine", "Developer", "Install location"), byTab.getValue(PageTab.DETAILS).map { it.title })
        // A row is placed by the tab it names, never by its title: an Overview title on Extras stays on Extras.
        assertEquals(listOf("Times played"), groupRowsByTab(listOf(PageFact("Times played", tab = PageTab.EXTRAS)), emptyList()).getValue(PageTab.EXTRAS).map { it.title })
    }

    @Test
    fun theSourceRowsLiveUnderVersionsAndOpenSourcePageNeedsAKnownPage() {
        val source = UpdateSources.Source("forum", "Forum thread", null)
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
    fun playTimeIsShortInTheHeroCaption() {
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
        val strip = factsStrip(
            played, now, folderSizeBytes = 2048, installedVersion = "0.9.5", runsWith = "Wine", runsWithReason = "Your choice for this game",
        )
        assertEquals(
            listOf(
                StripFact("Last played", "2 days ago"),
                StripFact("Play time", "1 h"),
                StripFact("Version", "0.9.5, v0.9.6 is available"),
                StripFact("Size", "2 KB"),
                StripFact("Runs with", "Wine", "Your choice for this game"),
            ),
            strip,
        )

        val bare = factsStrip(entry(), now, folderSizeBytes = null, installedVersion = null, runsWith = null)
        assertEquals(listOf(StripFact("Last played", "Never")), bare)

        // Counted but not timed: the count is Overview's "Times played", not a play time.
        val counted = factsStrip(entry().copy(playCount = 14), now, folderSizeBytes = null, installedVersion = null, runsWith = null)
        assertEquals(listOf(StripFact("Last played", "Never")), counted)
    }

    @Test
    fun heroArtNarrowerThan320PixelsIsNeverBlownUp() {
        assertTrue(isNarrowHeroArt(319f))
        assertFalse(isNarrowHeroArt(320f))
        assertFalse(isNarrowHeroArt(0f))
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

    @Test
    fun `the cloud saves row says when they synced, that it failed, that the device is offline, or that the store has none`() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        val synced = dev.droidtop.library.stores.StoreSaves.LastSync(okAt = now - 2 * day, failedAt = null)
        assertEquals("Synced 2 days ago" to "Select to sync now", cloudSavesLine("Steam", true, synced, online = true, now = now))
        assertEquals("Offline" to "Not synced since 2 days ago", cloudSavesLine("Steam", true, synced, online = false, now = now))
        val failed = synced.copy(failedAt = now - 1000)
        assertEquals("Last sync failed" to "Select to retry", cloudSavesLine("Steam", true, failed, online = true, now = now))
        assertEquals("Not synced yet" to "Select to sync now", cloudSavesLine("Steam", true, null, online = true, now = now))
        assertEquals("Not supported" to "GOG cloud saves are not supported", cloudSavesLine("GOG", false, null, online = true, now = now))
    }

    @Test
    fun `the cloud saves row draws its state too, syncing, offline, failed or synced`() {
        val synced = dev.droidtop.library.stores.StoreSaves.LastSync(okAt = 1L, failedAt = null)
        assertEquals(dev.droidtop.library.settings.StatusMark.WORKING, cloudSavesMark(true, synced, online = true, syncing = true))
        assertEquals(dev.droidtop.library.settings.StatusMark.OFFLINE, cloudSavesMark(true, synced, online = false, syncing = false))
        assertEquals(dev.droidtop.library.settings.StatusMark.ATTENTION, cloudSavesMark(true, synced.copy(failedAt = 2L), online = true, syncing = false))
        assertEquals(dev.droidtop.library.settings.StatusMark.SYNCED, cloudSavesMark(true, synced, online = true, syncing = false))
        assertEquals(null, cloudSavesMark(true, null, online = true, syncing = false))
        assertEquals(null, cloudSavesMark(false, synced, online = true, syncing = false))
    }

    @Test
    fun `details lead with where it came from, then ownership, size and cloud saves, and More last`() {
        val rows = listOf("Install location", "Runner", MORE_ROW, "Cloud saves", "Size", "Ownership", "Source", "Store id").map { PageFact(it, tab = PageTab.DETAILS) }
        assertEquals(
            listOf("Source", "Ownership", "Size", "Cloud saves", "Runner", MORE_ROW, "Install location", "Store id"),
            groupRowsByTab(rows, emptyList()).getValue(PageTab.DETAILS).map { it.title },
        )
    }
}

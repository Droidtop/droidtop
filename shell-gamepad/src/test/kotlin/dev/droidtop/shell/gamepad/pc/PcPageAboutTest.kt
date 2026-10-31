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
        val a = PageTab.ACTIVITY
        val rows = listOf(
            "Times played" to a, "Install location" to d, "Version" to v, "Latest" to v, "Update" to v, "Runner" to d,
            "Manual" to PageTab.EXTRAS, "Engine" to d, "Developer" to d, "About" to o, "Last played" to a,
            OPEN_SOURCE_ROW to PageTab.THREAD,
        ).map { (title, tab) -> PageFact(title, tab = tab) } + PageFact("Official site", tab = PageTab.EXTRAS)
        val parts = listOf(PageFact("Parts", tab = o), PageFact("Week 1", tab = o))
        val byTab = groupRowsByTab(rows, parts)

        assertEquals(PageTab.values().toList(), byTab.keys.toList())
        assertEquals(rows.size + parts.size, byTab.values.sumOf { it.size })
        assertEquals(listOf("Parts", "Week 1", "About"), byTab.getValue(PageTab.OVERVIEW).map { it.title })
        assertEquals(listOf("Times played", "Last played"), byTab.getValue(PageTab.ACTIVITY).map { it.title })
        assertEquals(listOf(OPEN_SOURCE_ROW), byTab.getValue(PageTab.THREAD).map { it.title })
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
        // The source's own page is the Show thread tab's row; linking it stays under Versions.
        assertEquals(listOf(PageTab.VERSIONS, PageTab.THREAD), linked.map { it.tab })
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
        // When and how long it was played are Activity's rows, not the strip's.
        val strip = factsStrip(played, folderSizeBytes = 2048, installedVersion = "0.9.5", runsWith = "Wine")
        assertEquals(
            listOf(
                "Version" to "0.9.5, v0.9.6 is available",
                "Size" to "2 KB",
                "Runs with" to "Wine",
            ),
            strip,
        )

        assertEquals(emptyList<Pair<String, String>>(), factsStrip(entry(), folderSizeBytes = null, installedVersion = null, runsWith = null))
    }

    @Test
    fun activityListsWhenHowLongAndHowOftenOnlyOnceItWasPlayed() {
        val now = 100 * day
        val played = entry().copy(lastPlayedEpochMs = now - 2 * day - 1000, playtimeSeconds = 3600, playCount = 14)
        val rows = activityRows(played, now)
        assertEquals(listOf("Last played" to "2 days ago", "Play time" to "1 h", "Times played" to "14"), rows.map { it.title to it.value })
        assertTrue(rows.all { it.tab == PageTab.ACTIVITY })
        // Counted but not timed: the count is a row of its own, not a play time.
        assertEquals(listOf("Times played"), activityRows(entry().copy(playCount = 3), now).map { it.title })
        // Never played: no rows, so the tab says its empty line.
        assertEquals(emptyList<PageFact>(), activityRows(entry(), now))
    }

    @Test
    fun everyTabIsDrawnAndAnEmptyOneHasItsLine() {
        assertEquals(
            listOf("Overview", "Versions", "Activity", "Extras", "Details", "Show thread"),
            PageTab.values().map { it.label },
        )
        assertTrue(PageTab.values().all { tab -> PageKind.values().all { tab.emptyLine(it).isNotBlank() } })
        // Nothing at all still gives every tab, each with no rows.
        val byTab = groupRowsByTab(emptyList(), emptyList())
        assertEquals(PageTab.values().toList(), byTab.keys.toList())
        assertTrue(byTab.values.all { it.isEmpty() })
    }

    private val ready = PcPlayState(verb = "Play", detail = "", pressable = true, ready = true)

    @Test
    fun theStatusRowSaysReadyUpdateSavesAndAchievementsFromWhatThePageKnows() {
        val statuses = pageStatuses(
            entry().copy(availableUpdate = "0.9.6", fieldSources = mapOf("description" to "IGDB")),
            ready,
            runsWith = "Wine",
            cloudSaves = "Synced 2 days ago" to dev.droidtop.library.settings.StatusMark.SYNCED,
            saves = "3 save files, today",
            achievements = "4 of 10",
            achievementsSignIn = false,
        )
        assertEquals(
            listOf(PageStatusKind.READY, PageStatusKind.UPDATE, PageStatusKind.CLOUD_SAVES, PageStatusKind.SAVES, PageStatusKind.ACHIEVEMENTS),
            statuses.map { it.kind },
        )
        assertEquals("Ready to play with Wine", statuses[0].label)
        assertEquals("Update: v0.9.6 is available", statuses[1].label)
        assertEquals("Cloud saves: Synced 2 days ago", statuses[2].label)
        assertEquals(PageStatusTone.GOOD, statuses[2].tone)
        assertEquals(dev.droidtop.library.settings.StatusMark.SYNCED, statuses[2].mark)
        assertEquals("Saves: 3 save files, today", statuses[3].label)
        assertEquals("Achievements: 4 of 10", statuses[4].label)
    }

    @Test
    fun theStatusRowSaysWhatWaitsOnThePerson() {
        val notReady = PcPlayState(verb = "Set up Windows", detail = "Windows games need a one-time setup", pressable = true, ready = false)
        val statuses = pageStatuses(
            entry(),
            notReady,
            runsWith = null,
            cloudSaves = "Last sync failed" to dev.droidtop.library.settings.StatusMark.ATTENTION,
            saves = null,
            achievements = null,
            achievementsSignIn = true,
        )
        assertEquals("Not ready to play: Windows games need a one-time setup", statuses.first { it.kind == PageStatusKind.READY }.label)
        assertEquals(PageStatusTone.WAITING, statuses.first { it.kind == PageStatusKind.CLOUD_SAVES }.tone)
        assertEquals("Achievements: sign in to see them", statuses.first { it.kind == PageStatusKind.ACHIEVEMENTS }.label)
        // Nothing scraped yet: its glyph says so, and Edit metadata holds Scrape.
        assertTrue(statuses.any { it.kind == PageStatusKind.NOT_SCRAPED })
        // A state the page does not know is not a glyph: no saves row, no update.
        assertTrue(statuses.none { it.kind == PageStatusKind.SAVES || it.kind == PageStatusKind.UPDATE })
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
        assertEquals("Play", pageActionLabel(PageButton.PRIMARY, "Play"))
        assertEquals("Install", pageActionLabel(PageButton.PRIMARY, "Install"))
        assertEquals("Edit metadata", pageActionLabel(PageButton.EDIT_METADATA, "Play"))
        assertEquals("Options", pageActionLabel(PageButton.OPTIONS, "Play"))
        assertEquals("Favourite", pageActionLabel(PageButton.FAVOURITE, "Play"))
    }

    @Test
    fun theBandHasEditMetadataOnlyOnAPageWithMetadataToEdit() {
        assertEquals(
            listOf(PageButton.PRIMARY, PageButton.EDIT_METADATA, PageButton.OPTIONS, PageButton.FAVOURITE),
            pageButtons(hasEditMetadata = true),
        )
        // An app's page: Play, Options and the star, the cursor never stopping on a button that is not drawn.
        assertEquals(listOf(PageButton.PRIMARY, PageButton.OPTIONS, PageButton.FAVOURITE), pageButtons(hasEditMetadata = false))
    }

    @Test
    fun theScrapeRowSaysWhatItDoesAndTakesNoPressWhileItRuns() {
        val unscraped = entry()
        assertEquals(SCRAPE_ROW, scrapeRow(unscraped, null, "the PC sources") {}.title)
        assertEquals("Not scraped yet. Looks this game up in the PC sources", scrapeRow(unscraped, null, "the PC sources") {}.detail)
        val scraped = entry(description = "A game", fieldSources = mapOf("description" to "IGDB"))
        assertEquals("Looks this game up in the console scrapers again", scrapeRow(scraped, null, "the console scrapers") {}.detail)
        // While one runs the row says so and A does nothing; what it said stays as the row's line.
        assertNull(scrapeRow(scraped, SCRAPING, "the PC sources") {}.onSelect)
        assertEquals("Scraped 1 game", scrapeRow(scraped, "Scraped 1 game", "the PC sources") {}.detail)
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

package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "About this game" helpers the focused-game panel draws
 * (PcLibraryView.kt; docs/SPEC.md 7h), restored from the PcGameAbout.kt
 * 444271f2 deleted: the facts a scrape leaves, the ES-DE date format,
 * and the one line saying where each field came from. Pure functions
 * over in-memory [LibraryEntry] data, so the wording a player reads is
 * pinned here rather than only on hardware.
 */
class FocusedPanelAboutTest {
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
}

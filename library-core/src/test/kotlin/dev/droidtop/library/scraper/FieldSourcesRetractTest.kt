package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

/** A source that now answers "no match" takes back what it wrote earlier, never what the person edited. */
class FieldSourcesRetractTest {
    private val had = FieldSources.encode(
        mapOf(
            FieldSources.DESCRIPTION to "TheGamesDB",
            FieldSources.RELEASE_DATE to "TheGamesDB",
            FieldSources.GENRE to FieldSources.EDITED,
            FieldSources.DEVELOPER to "libretro database",
            FieldSources.COVER to "TheGamesDB",
        ),
    )

    @Test
    fun onlyTheUnmatchedSourcesEditableFields() {
        assertEquals(
            setOf(FieldSources.DESCRIPTION, FieldSources.RELEASE_DATE),
            FieldSources.retracted(had, "TheGamesDB"),
        )
        assertEquals(emptySet<String>(), FieldSources.retracted(had, "ScreenScraper"))
    }

    @Test
    fun aFreshMatchReplacesEveryScrapedFieldButNotEditsOrAnImportedGamelist() {
        val sources = FieldSources.encode(
            mapOf(
                FieldSources.DESCRIPTION to "TheGamesDB",
                FieldSources.GENRE to FieldSources.EDITED,
                FieldSources.PUBLISHER to FieldSources.GAMELIST,
                FieldSources.COVER to "TheGamesDB",
            ),
        )
        // The release date has a value but no recorded source: written before sources were recorded, by a scrape.
        val filled = setOf(FieldSources.DESCRIPTION, FieldSources.GENRE, FieldSources.PUBLISHER, FieldSources.RELEASE_DATE)
        assertEquals(setOf(FieldSources.DESCRIPTION, FieldSources.RELEASE_DATE), FieldSources.superseded(sources, filled))
        assertEquals(emptySet<String>(), FieldSources.superseded(sources, emptySet()))
    }

    @Test
    fun filledNamesTheFieldsThatHoldAValue() {
        val row = dev.droidtop.library.consoles.GameMetadataEntity(id = "/r/gbc/a.gbc")
            .copy(description = "text", releaseDate = "20230309T000000")
        assertEquals(setOf(FieldSources.DESCRIPTION, FieldSources.RELEASE_DATE), FieldSources.filled(row))
    }

    @Test
    fun withdrawnFieldsLoseTheirSource() {
        val after = FieldSources.decode(FieldSources.withdraw(had, setOf(FieldSources.DESCRIPTION, FieldSources.RELEASE_DATE)))
        assertEquals(
            mapOf(
                FieldSources.GENRE to FieldSources.EDITED,
                FieldSources.DEVELOPER to "libretro database",
                FieldSources.COVER to "TheGamesDB",
            ),
            after,
        )
    }
}

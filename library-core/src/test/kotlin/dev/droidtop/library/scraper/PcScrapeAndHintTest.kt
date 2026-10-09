package dev.droidtop.library.scraper

import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.integrations.AcquireEngineHint
import dev.droidtop.pluginhost.MetadataFields
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata plugins in the PC scrape pass, and a source's engine hint on a download (docs/plugin-api.md 1.6, 3 A3). */
class PcScrapeAndHintTest {
    private fun fields(source: String, description: String? = null, developer: String? = null, genre: String? = null, rating: Float? = null) =
        MetadataFields(source, description, developer, null, genre, null, null, rating)

    @Test
    fun `a plugin fills only what the built-in sources left empty, first plugin first`() {
        val row = GameMetadataEntity(id = "/games/pc/Eternum", developer = "From Steam")
        val (filled, sources) = PcPluginFields.fill(
            row,
            listOf(
                fields("F95zone", description = "About it", developer = "Caribdis", genre = "Ren'Py"),
                fields("Other", description = "Other words", rating = 0.9f),
            ),
        )
        assertEquals("About it", filled.description)
        assertEquals("From Steam", filled.developer)
        assertEquals("Ren'Py", filled.genre)
        assertEquals(0.9f, filled.rating)
        assertEquals(mapOf("description" to "F95zone", "genre" to "F95zone", "rating" to "Other"), sources)
    }

    @Test
    fun `a field the person edited is never filled`() {
        val row = GameMetadataEntity(id = "x", fieldSources = FieldSources.merge(null, mapOf("description" to FieldSources.EDITED)))
        val (filled, sources) = PcPluginFields.fill(row, listOf(fields("F95zone", description = "About it")))
        assertNull(filled.description)
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `an engine hint pins the placed file and the folder its archive unpacks to, and only known ids count`() {
        assertEquals(
            listOf(File("/g/pc/Eternum-0.9.5-pc.zip").absolutePath, File("/g/pc/Eternum-0.9.5-pc").absolutePath),
            AcquireEngineHint.pinnedPaths(File("/g/pc/Eternum-0.9.5-pc.zip")),
        )
        assertEquals(
            listOf(File("/g/pc/Game.tar.gz").absolutePath, File("/g/pc/Game").absolutePath),
            AcquireEngineHint.pinnedPaths(File("/g/pc/Game.tar.gz")),
        )
        assertEquals(listOf(File("/g/pc/Game").absolutePath), AcquireEngineHint.pinnedPaths(File("/g/pc/Game")))
        assertEquals("renpy", AcquireEngineHint.valid(" renpy "))
        assertNull(AcquireEngineHint.valid("RenPy"))
        assertNull(AcquireEngineHint.valid(null))
    }
}

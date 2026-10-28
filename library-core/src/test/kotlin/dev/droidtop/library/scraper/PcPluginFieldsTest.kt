package dev.droidtop.library.scraper

import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.pluginhost.MetadataFields
import org.junit.Assert.assertEquals
import org.junit.Test

/** A metadata plugin's fields: gaps only, except a refresh with the plugin as the chosen source (Droidtop/tracker#67). */
class PcPluginFieldsTest {
    private val f95 = MetadataFields("F95zone", "New text", "New dev", null, "Visual novel", null, null, null)

    @Test fun `a pass fills only what is empty`() {
        val row = GameMetadataEntity(id = "/g/Foo", description = "Old text")
        val (filled, sources) = PcPluginFields.fill(row, listOf(f95))
        assertEquals("Old text", filled.description)
        assertEquals("New dev", filled.developer)
        assertEquals(mapOf(FieldSources.DEVELOPER to "F95zone", FieldSources.GENRE to "F95zone"), sources)
    }

    @Test fun `a refresh with the plugin as the source replaces what is there, but never what the person edited`() {
        val edited = FieldSources.encode(mapOf(FieldSources.DEVELOPER to FieldSources.EDITED))
        val row = GameMetadataEntity(id = "/g/Foo", description = "Old text", developer = "Mine", fieldSources = edited)
        val (filled, sources) = PcPluginFields.fill(row, listOf(f95), replace = true)
        assertEquals("New text", filled.description)
        assertEquals("Mine", filled.developer)
        assertEquals(setOf(FieldSources.DESCRIPTION, FieldSources.GENRE), sources.keys)
    }

    @Test fun `a refresh keeps a field the plugin says nothing about`() {
        val row = GameMetadataEntity(id = "/g/Foo", publisher = "Kept")
        assertEquals("Kept", PcPluginFields.fill(row, listOf(f95), replace = true).first.publisher)
    }
}

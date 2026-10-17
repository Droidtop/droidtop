package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plugin's cover (docs/plugin-api.md 3 A3): fetched by droidtop, only from the plugin's sites, only a picture. */
class PluginImagesTest {
    private val f95 = listOf("f95zone.to", "attachments.f95zone.to", "api.f95checker.dev")

    @Test
    fun `a cover comes over https from one of the plugin's own sites only`() {
        assertTrue(PluginImages.allowed("https://attachments.f95zone.to/2024/01/cover.png", f95))
        assertFalse(PluginImages.allowed("http://attachments.f95zone.to/2024/01/cover.png", f95))
        assertFalse(PluginImages.allowed("https://images.example/cover.png", f95))
        assertFalse(PluginImages.allowed("https://evil.attachments.f95zone.to.example/cover.png", f95))
        assertTrue(PluginImages.allowed("https://cdn.example.org/a.jpg", listOf("*.example.org")))
    }

    @Test
    fun `only a png, jpeg, webp or gif is kept`() {
        assertEquals("png", PluginImages.kindOf(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("jpeg", PluginImages.kindOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("webp", PluginImages.kindOf("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("gif", PluginImages.kindOf("GIF89a".toByteArray()))
        assertNull(PluginImages.kindOf("<html><body>Just a moment".toByteArray()))
        assertNull(PluginImages.kindOf(ByteArray(0)))
    }
}

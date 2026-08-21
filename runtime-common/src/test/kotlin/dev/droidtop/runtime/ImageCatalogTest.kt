package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Exercises real parsing of the exact bundled catalog file (loaded from the
 * classpath — see runtime-common/build.gradle.kts wiring `src/main/assets`
 * into the test sourceSet's resources) so a malformed
 * `image-catalog.json` fails a fast JVM test instead of only surfacing as a
 * runtime crash the first time [BundledImageCatalog.load] runs on-device.
 */
class ImageCatalogTest {
    private fun loadBundledCatalogText(): String =
        javaClass.classLoader!!.getResourceAsStream("image-catalog.json")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `bundled catalog parses and has at least one PRIMARY entry`() {
        val catalog = BundledImageCatalog.parse(loadBundledCatalogText())

        assertTrue("catalog should have at least one entry", catalog.entries.isNotEmpty())
        assertTrue(
            "catalog should have at least one PRIMARY (or BOTH) entry — DesktopSessionService.selectPrimaryImage() requires it",
            catalog.entries.any { it.role == ImageCatalogRole.PRIMARY || it.role == ImageCatalogRole.BOTH },
        )
    }

    @Test
    fun `entry ids are unique`() {
        val catalog = BundledImageCatalog.parse(loadBundledCatalogText())
        val duplicates = catalog.entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) fail("duplicate catalog entry ids: $duplicates")
    }

    @Test
    fun `toRootfsImage carries the entry's reference through unchanged`() {
        val entry = ImageCatalogEntry(
            id = "test-entry",
            os = "alpine",
            osVersion = "3.20",
            desktopEnvironment = "sway",
            role = ImageCatalogRole.PRIMARY,
            imageReference = "docker.io/library/alpine:3.20",
            verified = false,
        )

        val image = entry.toRootfsImage()

        assertEquals(entry.imageReference, image.reference)
        assertEquals(null, image.digest)
    }

    @Test
    fun `a minimal hand-written catalog round-trips`() {
        val text = """
            {
              "version": 1,
              "entries": [
                {
                  "id": "x",
                  "os": "alpine",
                  "osVersion": "edge",
                  "role": "SIBLING",
                  "imageReference": "docker.io/library/alpine:edge",
                  "verified": false
                }
              ]
            }
        """.trimIndent()

        val catalog = BundledImageCatalog.parse(text)

        assertEquals(1, catalog.entries.size)
        val entry = catalog.entries.single()
        assertEquals(null, entry.desktopEnvironment) // omitted in the JSON, defaults to null
        assertEquals(ImageCatalogRole.SIBLING, entry.role)
    }
}

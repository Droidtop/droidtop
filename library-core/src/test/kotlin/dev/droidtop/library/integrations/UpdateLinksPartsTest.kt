package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.AcquireDownloadDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

/** A release in parts, gathered part by part, is unpacked by the one rule over all its files (docs/plugin-api.md 1.10). */
class UpdateLinksPartsTest {
    private fun file(name: String) = AcquireDownloadDescriptor(url = "https://host.example/$name", headers = emptyMap(), fileName = name, sha256 = null, size = null)

    @Test
    fun `the numbered parts of one archive are unpacked together, whatever brought each`() {
        val joined = UpdateLinks.joined(listOf(file("Game.7z.001"), file("Game.7z.002"), file("Game.7z.003")))
        assertEquals(listOf(true, true, true), joined.map { it.unpack })
    }

    @Test
    fun `files that are no one archive are placed as they are`() {
        assertEquals(listOf(false, false), UpdateLinks.joined(listOf(file("Game.exe"), file("readme.txt"))).map { it.unpack })
        assertEquals(true, UpdateLinks.unpackFor(listOf("Game.zip")))
        assertEquals(false, UpdateLinks.unpackFor(emptyList()))
    }

    @Test
    fun `rar volumes are one split set and unpacked together`() {
        // FitGirl's releases come as Name.part1.rar, Name.part2.rar: placeSplitSet unpacks them into one folder.
        val joined = UpdateLinks.joined(listOf(file("Sample_Game.part1.rar"), file("Sample_Game.part2.rar")))
        assertEquals(listOf(true, true), joined.map { it.unpack })
    }
}

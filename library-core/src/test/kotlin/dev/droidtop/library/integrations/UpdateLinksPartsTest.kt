package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.AcquireDownloadDescriptor
import dev.droidtop.library.UpdateSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun part(n: Int, url: String?) = UpdateSources.Download.Part("Part $n", url, null, null)

    @Test
    fun `an expired part is the same part of the release asked again`() {
        val before = listOf(part(1, "https://host.example/a1"), part(2, "https://host.example/a2"))
        val fresh = listOf(part(1, "https://host.example/b1"), part(2, "https://host.example/b2"))
        assertEquals("https://host.example/b2", UpdateLinks.partAgain(before, 1, fresh))
    }

    @Test
    fun `a release with a different number of parts, or the same expired address, gives none`() {
        val before = listOf(part(1, "https://host.example/a1"), part(2, "https://host.example/a2"))
        assertNull(UpdateLinks.partAgain(before, 0, listOf(part(1, "https://host.example/b1"))))
        assertNull(UpdateLinks.partAgain(before, 0, before))
        assertNull(UpdateLinks.partAgain(before, 0, listOf(part(1, null), part(2, "https://host.example/b2"))))
    }
}

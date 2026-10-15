package dev.droidtop.runtime.windows.utils

import dev.droidtop.runtime.windows.RuntimeDownloads
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Component downloads try each location in order (docs/SPEC.md 9); the catalog's signature is tested in :runtime-common (ChainSignatureTest). */
class RuntimeDownloadsTest {
    @Test
    fun downloadsFallBackAcrossLocationsInOrder() {
        val tried = mutableListOf<String>()
        val got = RuntimeDownloads.firstThatWorks(listOf("ours", "maker", "other")) { url ->
            tried += url
            if (url == "ours") throw IOException("did not match its published checksum")
            url
        }
        assertEquals("maker", got)
        assertEquals(listOf("ours", "maker"), tried)
    }

    @Test(expected = IOException::class)
    fun downloadsFailWhenNoLocationWorks() {
        RuntimeDownloads.firstThatWorks(listOf("ours", "maker")) { throw IOException("HTTP 404") }
    }

    @Test
    fun anEntryWithoutAListUsesItsUrl() {
        assertEquals(listOf("u"), ManifestEntry(id = "i", name = "n", url = "u").downloadUrls())
        assertEquals(listOf("a", "b"), CatalogFile(url = "a", urls = listOf("a", "b")).downloadUrls())
    }
}

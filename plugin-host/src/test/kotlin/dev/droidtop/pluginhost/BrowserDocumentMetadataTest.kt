package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserDocumentMetadataTest {
    private fun document() = JSONObject().put("kind", "document").put("status", 200).put("finalUrl", "https://forum.example/thread")
        .put("contentType", "application/json").put("bodyBytes", 2).put("truncated", false).put("effectiveUA", "Gecko/160")
        .put("cookies", JSONObject().put("forum.example", "cf_clearance=fixture"))

    @Test fun `document metadata must attest observed status and a complete bounded body`() {
        val accepted = BrowserDocumentMetadata.parse(document().toString(), "forum.example", 131072)
        assertEquals(200, accepted.status)
        assertEquals(2, accepted.bodyBytes)
        assertEquals("Gecko/160", accepted.userAgent)
        for (bad in listOf(
            document().apply { remove("status") },
            document().put("status", "200"),
            document().put("status", 200.5),
            document().put("truncated", true),
            document().apply { remove("truncated") },
            document().put("bodyBytes", 131073),
            document().put("cookies", JSONObject().put("another.example", "secret=1")),
            document().put("effectiveUA", "UA\r\nCookie:secret"),
            document().put("effectiveUA", 160),
            document().put("cookies", "not a jar"),
            document().put("finalUrl", "https://user:password@forum.example/"),
            document().put("script", "document.cookie"),
        )) assertTrue(bad.toString(), runCatching { BrowserDocumentMetadata.parse(bad.toString(), "forum.example", 131072) }.isFailure)
    }

    @Test fun `only an unparsed terminal failure may omit its final address`() {
        val failed = JSONObject().put("kind", "failed").put("reason", "Unsupported request")
        assertEquals("https://forum.example/thread", BrowserDocumentMetadata.parse(failed.toString(), "forum.example", 100, "https://forum.example/thread").finalUrl)
        assertTrue(runCatching { BrowserDocumentMetadata.parse(document().apply { remove("finalUrl") }.toString(), "forum.example", 100, "https://forum.example/thread") }.isFailure)
    }
}

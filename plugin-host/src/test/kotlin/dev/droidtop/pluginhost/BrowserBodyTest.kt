package dev.droidtop.pluginhost

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserBodyTest {
    private fun metadata() = JSONObject().put("sourceUrl", "https://example.com/page").put("fileName", "Game.zip").put("size", 3).put("mimeType", "application/zip")

    @Test fun `shared response metadata contract cases`() {
        val cases = javaClass.classLoader!!.getResourceAsStream("web-body-metadata-cases.json")!!.use { org.json.JSONArray(it.readBytes().toString(Charsets.UTF_8)) }
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(case.getString("label"), case.getBoolean("valid"), BrowserBodyMetadata.parse(case.getJSONObject("metadata").toString()) != null)
        }
    }

    @Test fun `body metadata grants no path or request authority`() {
        assertNotNull(BrowserBodyMetadata.parse(metadata().toString()))
        for (bad in listOf(
            metadata().put("fileName", "../Game.zip"),
            metadata().put("sourceUrl", "blob:https://example.com/1"),
            metadata().put("sourceUrl", "https://user:secret@example.com/"),
            metadata().put("size", BrowserBodyMetadata.MAX_BYTES + 1),
            metadata().put("mimeType", "application/zip\r\nCookie:secret"),
            metadata().put("path", "/sdcard/secret"),
        )) assertNull(bad.toString(), BrowserBodyMetadata.parse(bad.toString()))
    }

    @Test fun `only exact or bounded unknown response bodies finish`() {
        val output = ByteArrayOutputStream()
        assertEquals(3L, BrowserBodyCopy.receive(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 3, 10))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(3L, BrowserBodyCopy.receive(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), -1, 10))
        for ((declared, limit) in listOf(4L to 10L, 2L to 10L, -1L to 2L)) {
            assertTrue(runCatching { BrowserBodyCopy.receive(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), declared, limit) }.isFailure)
        }
    }

    @Test fun `cancellation before reading stops a body without retries`() {
        var reads = 0
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int { reads++; return super.read(bytes, off, len) }
        }
        assertTrue(runCatching {
            BrowserBodyCopy.receive(input, ByteArrayOutputStream(), 3, 10, beforeRead = { throw kotlinx.coroutines.CancellationException("cancelled") })
        }.isFailure)
        assertEquals(0, reads)
    }
}

package dev.droidtop.pluginhost

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

}

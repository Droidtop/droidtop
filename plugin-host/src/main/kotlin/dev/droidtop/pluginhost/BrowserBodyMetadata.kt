package dev.droidtop.pluginhost

import org.json.JSONObject

/** A body offer describes bytes, never a file authority, page contents or replayable request. */
internal data class BrowserBodyMetadata(val sourceUrl: String, val fileName: String, val mimeType: String?, val size: Long) {
    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024 * 1024
        fun parse(text: String): BrowserBodyMetadata? = runCatching {
            require(text.length <= 8192)
            val json = JSONObject(text)
            require(json.keys().asSequence().toSet().all { it in setOf("sourceUrl", "fileName", "mimeType", "size") })
            val url = json.getString("sourceUrl")
            require(WebSurfaceEvents.safeUrl(url))
            val name = json.getString("fileName")
            require(AcquireFileName.clean(name) == name && name.none { it == '/' || it == '\\' || it.isISOControl() })
            val mime = if (json.isNull("mimeType")) null else json.getString("mimeType").takeIf { it.isNotBlank() }
            require(mime == null || (mime.length <= 256 && mime.none { it.isISOControl() }))
            val rawSize = json.opt("size")
            require(rawSize == null || (rawSize is Number && rawSize.toString().matches(Regex("-1|0|[1-9][0-9]*"))))
            val size = rawSize?.toString()?.toLong() ?: -1L
            require(size in -1..MAX_BYTES)
            BrowserBodyMetadata(url, name, mime, size)
        }.getOrNull()
    }
}

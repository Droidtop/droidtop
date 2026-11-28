package dev.droidtop.pluginhost

import org.json.JSONObject

/** Fixed rendered-document results; no request payload can introduce an evaluator or a page selector. */
internal data class BrowserDocumentMetadata(
    val kind: String,
    val status: Int?,
    val finalUrl: String,
    val contentType: String,
    val bodyBytes: Int,
    val userAgent: String?,
    val cookies: String?,
    val reason: String?,
) {
    companion object {
        fun parse(text: String, requestHost: String, maxBytes: Int, requestUrl: String? = null): BrowserDocumentMetadata {
            require(text.length <= 32768) { "The browser returned too much document metadata" }
            val json = JSONObject(text)
            require(json.keys().asSequence().all { it in setOf("kind", "status", "finalUrl", "contentType", "bodyBytes", "truncated", "effectiveUA", "cookies", "reason") }) { "The browser returned unsupported document fields" }
            val kind = json.getString("kind")
            require(kind in setOf("document", "challenged", "permission_denied", "failed")) { "The browser returned an unknown document outcome" }
            val finalUrl = if (kind == "failed" && !json.has("finalUrl")) requestUrl ?: error("No immutable request address") else json.getString("finalUrl")
            require(WebSurfaceEvents.safeUrl(finalUrl)) { "The browser returned an invalid document address" }
            fun header(key: String, limit: Int): String? {
                val raw = json.opt(key).takeUnless { json.isNull(key) } ?: return null
                require(raw is String) { "The browser returned invalid document metadata" }
                return raw.takeUnless { it.isBlank() }?.also {
                require(it.length <= limit && it.none { char -> char.isISOControl() }) { "The browser returned invalid document metadata" }
                }
            }
            fun number(key: String, default: Long?): Long? {
                val raw = json.opt(key).takeUnless { json.isNull(key) } ?: return default
                require(raw is Number && raw.toString().matches(Regex("0|[1-9][0-9]*"))) { "The browser returned an invalid numeric field" }
                return raw.toString().toLong()
            }
            val status = number("status", null)
            require(status == null || status in 100L..599L) { "The browser returned an invalid status" }
            require(kind != "document" || status != null) { "The browser did not observe the document's HTTP status" }
            val size = number("bodyBytes", 0) ?: 0
            require(size in 0..maxBytes.toLong()) { "The browser document exceeds its size limit" }
            require(kind != "document" || json.has("bodyBytes")) { "The browser did not declare its document size" }
            require(!json.optBoolean("truncated") && (kind != "document" || json.opt("truncated") == false)) { "The browser document exceeds its size limit or has no completion state" }
            require(!json.has("cookies") || json.isNull("cookies") || json.opt("cookies") is JSONObject)
            val jar = json.optJSONObject("cookies") ?: JSONObject()
            require(jar.keys().asSequence().all { it == requestHost }) { "The browser returned cookies outside the requested site" }
            require(!jar.has(requestHost) || jar.isNull(requestHost) || jar.opt(requestHost) is String)
            val cookies = jar.optString(requestHost).takeUnless { jar.isNull(requestHost) || it.isBlank() }?.also {
                require(it.length <= 16384 && it.none { char -> char.isISOControl() }) { "The browser returned an invalid cookie header" }
            }
            val type = header("contentType", 256)
            val agent = header("effectiveUA", 4096)
            require(kind != "document" || (type != null && agent != null)) { "The browser did not report its document type and user agent" }
            require(cookies == null || agent != null) { "The browser returned cookies without their user agent" }
            return BrowserDocumentMetadata(kind, status?.toInt(), finalUrl, type.orEmpty(), size.toInt(), agent, cookies, header("reason", 512))
        }
    }
}

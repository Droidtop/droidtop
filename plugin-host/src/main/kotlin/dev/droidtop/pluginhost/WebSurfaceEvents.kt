package dev.droidtop.pluginhost

import org.json.JSONObject

/** Surface control messages carry no page text, script, form data or credentials. */
internal object WebSurfaceEvents {
    fun safeUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        url.length <= 4096 && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && url.none { it.isISOControl() }
    }.getOrDefault(false)
    data class Event(val kind: String, val url: String? = null, val title: String? = null, val back: Boolean = false, val loading: Boolean = false, val message: String? = null)

    fun parse(text: String): Event? = runCatching {
        require(text.length <= 8192)
        val json = JSONObject(text)
        when (val kind = json.getString("type")) {
            "navigation" -> {
                val url = json.getString("url")
                require(safeUrl(url))
                val title = json.optString("title").take(512).replace(Regex("[\\p{Cntrl}]"), " ")
                Event(kind, url, title, json.optBoolean("canGoBack"))
            }
            "loading" -> Event(kind, loading = json.getBoolean("loading"))
            "error" -> {
                require(json.has("code"))
                Event(kind, message = json.optString("message").take(512).replace(Regex("[\\p{Cntrl}]"), " "))
            }
            else -> null
        }
    }.getOrNull()
}

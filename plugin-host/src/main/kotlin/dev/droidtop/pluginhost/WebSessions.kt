package dev.droidtop.pluginhost

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * A plugin's signed-in web session (docs/plugin-api.md 3 G3, `web.session@1`): the cookies a person's sign-in on a
 * site left in droidtop's own web view, kept for that plugin alone. The plugin never reads them: they are sealed in
 * its vault under a host key it cannot name ([VAULT_KEY]; plugin keys are `[A-Za-z0-9._-]`), put back into the web
 * view only while droidtop shows that plugin's session, and added by droidtop to the plugin's requests to the sites
 * it declared. Uninstalling the plugin deletes them with its vault.
 */
object WebSessions {
    const val PERMISSION = "web.session"

    /** The host-owned vault entry that holds a plugin's session. */
    const val VAULT_KEY = "@web.session"

    /** How long a captured download waits for the plugin's acquire reply to claim it. */
    const val CAPTURE_TTL_MS = 30L * 60 * 1000

    /** The sites a plugin's `web.session` entry lists (`domains`); each covers itself and its subdomains. */
    fun declaredDomains(declared: List<DeclaredPermission>): List<String> {
        val entry = declared.firstOrNull { it.id == PERMISSION } ?: return emptyList()
        val list = runCatching { JSONObject(entry.extra).optJSONArray("domains") }.getOrNull() ?: return emptyList()
        return List(list.length()) { list.optString(it).trim().lowercase().removePrefix("*.").removePrefix(".") }
            .filter { it.isNotEmpty() && it.contains('.') && !it.contains('/') }
            .distinct()
    }

    /** True when [host] is one of [domains] or below one. */
    fun covers(host: String, domains: List<String>): Boolean =
        domains.any { host == it || host.endsWith(".$it") }

    /** The declared domain [url] falls under, or null. Only https addresses are ever in a session. */
    fun domainFor(url: String, domains: List<String>): String? {
        if (!NetScope.isHttps(url)) return null
        val host = NetScope.hostOf(url) ?: return null
        return domains.filter { host == it || host.endsWith(".$it") }.maxByOrNull { it.length }
    }

    /** What is kept: the web view's user agent (a site may tie a session to it) and the cookies per declared domain. */
    data class Stored(
        val userAgent: String?,
        val cookies: Map<String, String>,
        /** The account's name as the plugin read it from the site (`web.session set_account`), for the account row; null until told. */
        val account: String? = null,
    ) {
        fun toJson(): String = JSONObject()
            .put("ua", userAgent ?: JSONObject.NULL)
            .put("cookies", JSONObject(cookies as Map<*, *>))
            .put("account", account ?: JSONObject.NULL)
            .toString()

        /** The `Cookie` header for [url], when it is under a domain with cookies. */
        fun cookieFor(url: String, domains: List<String>): String? =
            domainFor(url, domains)?.let { cookies[it] }?.takeIf { it.isNotBlank() }

        companion object {
            /** An account name fit to show: one line, at most 64 characters, no control characters; null otherwise. */
            fun accountName(text: String?): String? =
                text?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none { c -> c.isISOControl() } }

            fun parse(text: String?): Stored? = runCatching {
                val json = JSONObject(requireNotNull(text))
                val cookies = json.optJSONObject("cookies") ?: JSONObject()
                Stored(
                    userAgent = json.optString("ua").takeIf { !json.isNull("ua") && it.isNotBlank() },
                    cookies = cookies.keys().asSequence().associateWith { cookies.optString(it) }.filterValues { it.isNotBlank() },
                    account = accountName(json.optString("account").takeIf { !json.isNull("account") }),
                )
            }.getOrNull()
        }
    }

    /** A file a page started downloading in a plugin's session, waiting for that plugin's acquire reply. */
    data class Captured(
        val pluginId: String,
        val url: String,
        val fileName: String,
        val size: Long?,
        val mimeType: String?,
        /** The headers droidtop sends for it (cookies, user agent, referrer); never shown to the plugin. */
        val headers: Map<String, String>,
        val atMs: Long,
    )

    private val captured = ConcurrentHashMap<String, Captured>()

    /** Keeps [download] and returns the token the plugin hands back in its acquire reply (`download.session`). */
    fun keep(download: Captured): String {
        val now = download.atMs
        captured.entries.removeIf { now - it.value.atMs > CAPTURE_TTL_MS }
        val token = "w-" + UUID.randomUUID().toString()
        captured[token] = download
        return token
    }

    /** The capture [token] names, once, when it is [pluginId]'s, for [url], and not stale. */
    fun take(pluginId: String, token: String, url: String, nowMs: Long): Captured? {
        val found = captured[token] ?: return null
        if (found.pluginId != pluginId || found.url != url || nowMs - found.atMs > CAPTURE_TTL_MS) return null
        return captured.remove(token)
    }

    /**
     * A bare file name the download job accepts (`[A-Za-z0-9][A-Za-z0-9._-]*`, docs/plugin-view.schema.json
     * `acquireDownload`), from whatever the site called the file.
     */
    fun safeFileName(raw: String?): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "_").trimStart('.', '_', '-').take(MAX_NAME)
        return cleaned.ifEmpty { "download" }
    }

    /**
     * What the site calls the file: `Content-Disposition`'s `filename*` (RFC 5987) or `filename`, else the last part of
     * the address's path. Not yet made safe ([safeFileName]).
     */
    fun fileNameFor(url: String, contentDisposition: String?): String? {
        val header = contentDisposition.orEmpty()
        Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { match ->
            return runCatching { java.net.URLDecoder.decode(match.groupValues[1].trim(), "UTF-8") }.getOrNull()
        }
        Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(header)?.let { return it.groupValues[1] }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { return it.groupValues[1].trim() }
        return runCatching { java.net.URI(url).path?.substringAfterLast('/') }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private const val MAX_NAME = 120
}

/** What droidtop's web view did for a plugin: the session it ended with, and the download a page started, if any. */
data class WebSessionResult(
    /** False when the person backed out of a sign-in: nothing is stored. */
    val completed: Boolean,
    val session: WebSessions.Stored?,
    val download: WebSessionDownload?,
    /** Every download the page started, in order, when the request took more than one ([WebSessionRequest.maxDownloads]). */
    val downloads: List<WebSessionDownload> = listOfNotNull(download),
    /**
     * A link on a host droidtop serves ([dev.droidtop.pluginhost.hosts.HostLinks.served]) that the person followed in an
     * Open view before anything was taken: the view closed there, and the caller asks the host's resolvers for it.
     */
    val handoff: String? = null,
)

/** A download a page started in the web view, as the web view reported it. */
data class WebSessionDownload(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val size: Long?,
    val referer: String?,
    val cookie: String?,
    /** Further headers droidtop sends for it, never shown to a plugin: a file host's API gives the person's own account here (Droidtop/tracker#638). */
    val headers: Map<String, String> = emptyMap(),
    /** The file's SHA-256 when the host said it, checked once the file is whole. */
    val sha256: String? = null,
    /** How droidtop got it when the page did not ("via your PixelDrain", "direct from PixelDrain"), for the job's line. */
    val via: String? = null,
    /** The file's MD5 when the host says only that (GoFile), checked once the file is whole; a SHA-256 is preferred when both are given. */
    val md5: String? = null,
) {
    companion object {
        /** A file droidtop knows the address of, named as a page's download would be. */
        fun direct(url: String, fileName: String, size: Long?, headers: Map<String, String> = emptyMap(), sha256: String? = null, via: String? = null, md5: String? = null) =
            WebSessionDownload(
                url = url,
                userAgent = null,
                contentDisposition = "attachment; filename=\"${fileName.substringAfterLast('/').replace("\"", "")}\"",
                mimeType = null,
                size = size,
                referer = null,
                cookie = null,
                headers = headers,
                sha256 = sha256,
                via = via,
                md5 = md5,
            )
    }
}

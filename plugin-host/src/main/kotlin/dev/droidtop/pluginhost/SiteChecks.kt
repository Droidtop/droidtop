package dev.droidtop.pluginhost

import java.util.concurrent.ConcurrentHashMap

/**
 * A site that answers with a browser check (a Cloudflare challenge, DDoS-Guard) instead of the page (docs/SPEC.md 12a,
 * "A site that checks the browser"; Droidtop/tracker#600). The owner: "f95 has gotten more aggressive with cloudflare
 * filtering. F95checker has gotten better at allowing users to solve the captcha. we need to do the same". Generic,
 * for every plugin: droidtop tells the challenge from the page ([isChallenge]), the plugin gets one plain
 * `BROWSER_CHECK_REQUIRED` and never the challenge's HTML, droidtop remembers the site waits for a check ([pending]),
 * the person passes it in droidtop's web view, and the clearance the view earned ([Clearance]) goes with droidtop's
 * requests to that site after: the site's clearance cookies with the web view's own user agent, which the clearance is
 * bound to (F95Checker does the same: its forum requests carry its web view's user agent and cookies).
 */
object SiteChecks {
    /** The vault entry, host-owned like the session's, that keeps a plugin's clearances. */
    const val VAULT_KEY = "@web.clearance"

    /**
     * What a challenge page carries. F95Checker's two (modules/api.py `f95_cloudflare_captcha_messages`), Cloudflare's
     * challenge script, and DDoS-Guard's page.
     */
    private val MARKERS = listOf(
        "window._cf_chl_opt",
        "<title>Just a moment...</title>",
        "/cdn-cgi/challenge-platform/",
        "<title>DDoS-Guard</title>",
    )

    /** How much of an answer is looked at: a challenge says so near its top. */
    private const val LOOK_BYTES = 64 * 1024

    /** Whether an answer is a browser check rather than what was asked for. Pure. */
    fun isChallenge(status: Int, headers: Map<String, String>, body: ByteArray): Boolean {
        // Cloudflare names it outright on a challenge it serves.
        if (headers.entries.any { it.key.equals("cf-mitigated", ignoreCase = true) && it.value.trim().equals("challenge", ignoreCase = true) }) return true
        if (status != 403 && status != 429 && status != 503) return false
        val head = String(body, 0, minOf(body.size, LOOK_BYTES), Charsets.UTF_8)
        return MARKERS.any { head.contains(it, ignoreCase = true) }
    }

    /**
     * Whether a cookie is a check's clearance, which droidtop may send with any of the plugin's requests to that site:
     * Cloudflare's (`cf_clearance`, `__cf_bm`, `cf_chl_*`) and DDoS-Guard's (`__ddg*`). Never an account's cookie.
     */
    fun isClearanceCookie(name: String): Boolean {
        val n = name.trim()
        return n == "cf_clearance" || n.startsWith("__cf") || n.startsWith("cf_chl") || n.startsWith("__ddg")
    }

    /** Only the clearance cookies of a `Cookie` header ("a=1; b=2"), as a header; null when it has none. Pure. */
    fun clearanceOf(cookieHeader: String): String? =
        cookieHeader.split(';').map { it.trim() }
            .filter { pair -> pair.contains('=') && isClearanceCookie(pair.substringBefore('=')) }
            .joinToString("; ").takeIf { it.isNotEmpty() }

    /**
     * [header] with [extra]'s cookies added; either may be null. A check's clearance in [extra] replaces one of the same
     * name in [header]: [extra] is the newest the person earned, and a signed-in session kept from before carries an
     * older `cf_clearance` that made the site ask again right after a pass (Droidtop/tracker#600). Any other cookie
     * [header] already names keeps its own value. Pure.
     */
    fun mergeCookies(header: String?, extra: String?): String? {
        val added = extra.orEmpty().split(';').map { it.trim() }.filter { it.contains('=') }
        val replaced = added.map { it.substringBefore('=').trim() }.filter(::isClearanceCookie).toSet()
        val pairs = header.orEmpty().split(';').map { it.trim() }.filter { it.contains('=') && it.substringBefore('=').trim() !in replaced }
        val named = pairs.map { it.substringBefore('=').trim() }.toSet()
        return (pairs + added.filter { it.substringBefore('=').trim() !in named }).joinToString("; ").takeIf { it.isNotEmpty() }
    }

    /** The titles a check's page has while it checks: Cloudflare's and DDoS-Guard's. */
    private val CHECK_TITLES = listOf("just a moment", "attention required", "checking your browser", "ddos-guard", "please wait")

    /** Whether a page titled [title] is still a check rather than the site's page; an untitled page counts as one. Pure. */
    fun isCheckTitle(title: String?): Boolean {
        val t = title?.trim()?.lowercase().orEmpty()
        return t.isEmpty() || CHECK_TITLES.any { t.startsWith(it) || t.contains("| cloudflare") }
    }

    /**
     * Whether the person has passed a site's check, from what the web view shows: the site's own page (its title is
     * not a check's), and a pass cookie for it ([cookieHeader]: Cloudflare's `cf_clearance`, DDoS-Guard's `__ddg2_`).
     * The owner, 2026-10-24: "Can we have it autoclose when we detect the cloudflare pass?" Pure.
     */
    fun passed(title: String?, cookieHeader: String?): Boolean {
        if (isCheckTitle(title)) return false
        val names = cookieHeader.orEmpty().split(';').map { it.trim().substringBefore('=').trim() }
        return "cf_clearance" in names || "__ddg2_" in names
    }

    /**
     * A plugin's clearances: the user agent of the web view that passed the checks, and the clearance cookies per site
     * (each covering its subdomains). Kept in the plugin's vault under [VAULT_KEY], which a plugin cannot name.
     */
    data class Clearance(val userAgent: String?, val cookies: Map<String, String>) {
        /** The clearance for [host]'s site, or null. */
        fun forHost(host: String): String? = cookies.entries
            .filter { (site, _) -> host == site || host.endsWith(".$site") }
            .maxByOrNull { it.key.length }?.value

        /** This one with [other]'s sites and user agent added over it: a newer check wins. */
        fun plus(other: Clearance): Clearance = Clearance(other.userAgent ?: userAgent, cookies + other.cookies)

        fun toJson(): String = WebSessions.Stored(userAgent, cookies).toJson()

        companion object {
            fun parse(text: String?): Clearance? = WebSessions.Stored.parse(text)?.let { Clearance(it.userAgent, it.cookies) }

            /**
             * The clearance a web view left in [session]: its user agent and, per site, only the clearance cookies.
             * Null when it left none. Pure.
             */
            fun from(session: WebSessions.Stored?): Clearance? {
                val cookies = session?.cookies.orEmpty().mapNotNull { (site, header) -> clearanceOf(header)?.let { site to it } }.toMap()
                return Clearance(session?.userAgent, cookies).takeIf { cookies.isNotEmpty() }
            }
        }
    }

    fun read(vault: PluginVault, pluginId: String): Clearance? = Clearance.parse(vault.getHost(pluginId, VAULT_KEY))

    /** Keeps what [session] cleared for [pluginId], over what was kept; nothing when it cleared nothing. */
    fun keep(vault: PluginVault, pluginId: String, session: WebSessions.Stored?) {
        val earned = Clearance.from(session) ?: return
        val kept = read(vault, pluginId)
        vault.putHost(pluginId, VAULT_KEY, (kept?.plus(earned) ?: earned).toJson())
        earned.cookies.keys.forEach { site -> passed(pluginId, site) }
    }

    /**
     * The address each plugin last met a check at and has not passed since: what droidtop opens for the person, and
     * what tells droidtop's own surfaces (the record page, a download) to offer the check first. In memory: a restart
     * forgets it, and the next request meets the check again.
     */
    private val pending = ConcurrentHashMap<String, String>()

    fun pendingFor(pluginId: String): String? = pending[pluginId]

    fun met(pluginId: String, url: String) {
        pending[pluginId] = url
    }

    fun passed(pluginId: String, host: String) {
        val url = pending[pluginId] ?: return
        val at = NetScope.hostOf(url) ?: return
        if (at == host || at.endsWith(".$host") || host.endsWith(".$at")) pending.remove(pluginId)
    }

    /** The plain sentence a plugin gets for a check on [host]: never the page. */
    fun refusal(host: String): String = "$host checks the browser first; open it in droidtop to continue"
}

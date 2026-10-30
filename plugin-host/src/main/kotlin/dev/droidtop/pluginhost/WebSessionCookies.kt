package dev.droidtop.pluginhost

import android.webkit.CookieManager

/**
 * A web session's cookies in droidtop's one cookie store, for as long as a view of it is open: the visible
 * [WebSessionActivity] and the hidden [HiddenWebSession] put the same ones in and take the same ones out, so a page
 * gets the same session in either (Droidtop/tracker#783). Call on the main thread.
 */
internal object WebSessionCookies {
    /** Puts [request]'s session in for its domains, and droidtop's own signed-in download [sites] for theirs. */
    fun putIn(cookies: CookieManager, request: WebSessionRequest, sites: Map<String, String>?) {
        cookies.setAcceptCookie(true)
        request.domains.forEach { purge(cookies, it) }
        request.session?.cookies?.forEach { (domain, header) -> inject(cookies, domain, header) }
        // droidtop's own signed-in download sites (docs/SPEC.md 12a "Download sites", Droidtop/tracker#648), for their
        // domains only and only while the view is open.
        sites?.forEach { (domain, header) ->
            purge(cookies, domain)
            inject(cookies, domain, header)
        }
        cookies.flush()
    }

    /**
     * Takes them out again: the session the page left on [request]'s domains, with the view's [userAgent], and each of
     * the download [sites] as the page left it, for droidtop's vault. Nothing of either stays in the store.
     */
    fun takeOut(cookies: CookieManager, request: WebSessionRequest, sites: Map<String, String>?, userAgent: String?): Pair<WebSessions.Stored, List<Pair<String, String>>> {
        val session = WebSessions.Stored(
            userAgent = userAgent,
            cookies = request.domains.associateWith { domain -> cookies.getCookie("https://$domain").orEmpty() }.filterValues { it.isNotBlank() },
        )
        request.domains.forEach { purge(cookies, it) }
        val renewed = sites?.keys?.mapNotNull { domain ->
            val left = cookies.getCookie("https://$domain")
            purge(cookies, domain)
            left?.let { domain to it }
        }.orEmpty()
        cookies.flush()
        return session to renewed
    }

    /** Puts a stored `name=value; ...` header back for [domain] (and its subdomains). */
    fun inject(cookies: CookieManager, domain: String, header: String) {
        header.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
            cookies.setCookie("https://$domain", "$pair; Domain=.$domain; Path=/; Secure")
        }
    }

    /**
     * Takes every cookie droidtop's store sends to [domain] out of it: each name is expired as a host cookie and as a
     * domain cookie at the root path, which is how sign-in cookies are set. A cookie a site scoped to a deeper path
     * stays; it is sent only to that path of that site.
     */
    fun purge(cookies: CookieManager, domain: String) {
        val names = cookies.getCookie("https://$domain").orEmpty().split(';')
            .map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
        for (name in names) {
            cookies.setCookie("https://$domain", "$name=; Max-Age=0; Path=/")
            cookies.setCookie("https://$domain", "$name=; Max-Age=0; Path=/; Domain=.$domain")
            cookies.setCookie("https://$domain", "$name=; Max-Age=0; Path=/; Domain=$domain")
        }
    }
}

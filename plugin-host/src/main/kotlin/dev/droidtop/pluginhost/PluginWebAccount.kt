package dev.droidtop.pluginhost

import dev.droidtop.shared.sitecheck.Clearance
import dev.droidtop.shared.sitecheck.SiteCheck
import android.content.Context
import org.json.JSONObject

/**
 * droidtop's own handling of a plugin's site account (docs/plugin-api.md 3 G3, Droidtop/tracker#544 items 1 and 2):
 * the account row on the plugin's page, and the Sign in / Open the site action under a source that answered
 * `SIGN_IN_REQUIRED` or `BROWSER_CHECK_REQUIRED`. The plugin declares where its site signs people in (the `signIn`
 * address of its `web.session` permission) and reports that it needs a sign-in; droidtop draws the words and runs the
 * web view, so no plugin explains droidtop's own sign-in to the person. The session is the same one
 * [HostWebApis]'s `sign_in` keeps: the plugin's vault entry [WebSessions.VAULT_KEY], cookies the plugin never reads.
 */
object PluginWebAccount {
    /** Why a source could not answer until the person acts in the site's page. */
    enum class Need { SIGN_IN, BROWSER_CHECK }

    /**
     * The account row's state (docs/SPEC.md 12a "A plugin's site account"): [site] is the first declared site;
     * [signInUrl] the declared sign-in page, null when the plugin declared none (sign-in then opens the site's home
     * page, [checkUrl]); [name] the account's name when the plugin told it (`web.session set_account`); [grant] the
     * `web.session` permission's state.
     */
    data class Account(val site: String, val signInUrl: String?, val signedIn: Boolean, val grant: GrantState, val name: String? = null) {
        val allowed: Boolean get() = grant != GrantState.DENIED

        /** Switch account: sign out, then sign in again on the declared sign-in page. Only where the plugin declared one. */
        val canSwitch: Boolean get() = signInUrl != null

        /** The row's value: who is signed in, or that no one is. */
        val state: String get() = when {
            !signedIn -> "Not signed in"
            name != null -> name
            else -> "Signed in"
        }
    }

    /** The `signIn` address of [record]'s `web.session` entry, when it is https and on one of its declared sites. */
    fun signInUrl(record: PluginRecord): String? {
        val permissions = record.manifest.v2.permissions
        val entry = permissions.firstOrNull { it.id == WebSessions.PERMISSION } ?: return null
        val url = runCatching { JSONObject(entry.extra).optString("signIn").trim() }.getOrDefault("")
        val domains = WebSessions.declaredDomains(permissions)
        return url.takeIf { it.isNotEmpty() && WebSessions.domainFor(it, domains) != null }
    }

    /**
     * The `doneCookie` of [record]'s `web.session` entry: the cookie the site sets once someone is signed in, so the
     * sign-in view closes by itself then (as a plugin's own `sign_in` may ask). Null when it declared none.
     */
    fun doneCookie(record: PluginRecord): String? {
        val entry = record.manifest.v2.permissions.firstOrNull { it.id == WebSessions.PERMISSION } ?: return null
        return runCatching { JSONObject(entry.extra).optString("doneCookie").trim() }.getOrDefault("")
            .takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
    }

    /** Where a browser check is passed: the declared sign-in page's site, else the first declared site's home page. */
    fun checkUrl(record: PluginRecord): String? {
        signInUrl(record)?.let { url -> NetScope.hostOf(url)?.let { return "https://$it/" } }
        return WebSessions.declaredDomains(record.manifest.v2.permissions).firstOrNull()?.let { "https://$it/" }
    }

    /** [record]'s account, or null when it holds no `web.session` with a site. Reads the vault: off the main thread. */
    fun of(context: Context, record: PluginRecord): Account? {
        val domains = WebSessions.declaredDomains(record.manifest.v2.permissions)
        val site = domains.firstOrNull() ?: return null
        val snapshot = PluginGrants.forContext(context).read(record.manifest.id)
        val grant = PluginGrants.stateOf(record, snapshot, WebSessions.PERMISSION) ?: return null
        val stored = WebSessions.Stored.parse(PluginVault.forContext(context).getHost(record.manifest.id, WebSessions.VAULT_KEY))
        return Account(site, signInUrl(record), stored?.cookies?.isNotEmpty() == true, grant, stored?.account)
    }

    /**
     * Shows the site's page in the plugin's session for [need] and keeps the session the person ends with Done. Pressing
     * droidtop's Sign in for a plugin is the person allowing what `web.session` means ("Use your sign-in on its sites"),
     * so an unanswered (ASK) grant becomes GRANTED when a session is kept; a DENIED one is never overridden here (the
     * caller offers the plugin's Permissions instead). Blocks until the view closes: never on the main thread.
     * Returns whether a session is now kept.
     */
    fun open(context: Context, record: PluginRecord, need: Need): Boolean {
        val account = of(context, record)
        // A check is the site's, not the account's: a plugin with no web.session still gets one (Droidtop/tracker#600).
        if (need == Need.SIGN_IN && (account == null || !account.allowed)) return false
        if (account != null && !account.allowed) return false
        val metAt = SiteChecks.pendingFor(record.manifest.id)?.let { NetScope.hostOf(it) }
        val url = when (need) {
            Need.SIGN_IN -> account?.signInUrl ?: checkUrl(record)
            Need.BROWSER_CHECK -> metAt?.let { "https://$it/" } ?: checkUrl(record)
        } ?: return false
        val declared = WebSessions.declaredDomains(record.manifest.v2.permissions)
        val domains = (if (account != null) declared else emptyList()) +
            listOfNotNull(metAt, NetScope.hostOf(url)).filter { host -> !WebSessions.covers(host, declared) || account == null }
        val vault = PluginVault.forContext(context)
        val stored = if (account != null) WebSessions.Stored.parse(vault.getHost(record.manifest.id, WebSessions.VAULT_KEY)) else null
        // The view starts with what was cleared before, so a check still good is not asked again.
        val withClearance = withClearance(stored, SiteChecks.read(vault, record.manifest.id), domains.distinct())
        val mode = if (need == Need.SIGN_IN) WebSessionRequest.Mode.SIGN_IN else WebSessionRequest.Mode.CHECK
        // A check on a site the plugin signs in to also waits for the sign-in: a page past the check but signed out has
        // no download links (Droidtop/tracker#600, owner: "f95zone requires users to be logged in to see links").
        val done = if (account != null) doneCookie(record) else null
        val result = showKept(
            context,
            record,
            WebSessionRequest(record.manifest.label, mode, url, domains.distinct(), withClearance, done, signInUrl = account?.signInUrl),
        )
        // What the view cleared is kept whether or not the person pressed Done: droidtop's requests to the site send it.
        SiteChecks.keep(vault, record.manifest.id, result.session)
        val cleared = SiteChecks.clearanceFrom(result.session) != null
        if (account == null) return need == Need.BROWSER_CHECK && cleared
        val session = result.session?.takeIf { result.completed && it.cookies.isNotEmpty() }
            ?.let { it.copy(cookies = it.cookies.filterKeys { domain -> WebSessions.covers(domain, declared) }) }
            ?: return need == Need.BROWSER_CHECK && cleared
        // A sign-in may be another account, whose name the plugin tells again; a browser check keeps the name it had.
        val name = if (need == Need.SIGN_IN) null else stored?.account
        vault.putHost(record.manifest.id, WebSessions.VAULT_KEY, session.copy(account = name).toJson())
        if (account.grant == GrantState.ASK) PluginGrants.forContext(context).set(record.manifest.id, WebSessions.PERMISSION, GrantState.GRANTED)
        return true
    }

    /**
     * The web view for [request], with [record]'s process kept loaded while it is open (Droidtop/tracker#783): the person
     * may take minutes on the site's page, longer than [PluginProcesses.IDLE_MS], and what they came for goes on in the
     * plugin afterwards. A plugin with no process running is not started for it.
     */
    private fun showKept(context: Context, record: PluginRecord, request: WebSessionRequest): WebSessionResult {
        val kept = PluginProcesses.keep(record.manifest.id)
        try {
            return WebSessionActivity.show(context, request)
        } finally {
            if (kept) PluginProcesses.release(context, record.manifest.id)
        }
    }

    /** [stored] with [clearance]'s cookies added for each of [domains] it clears; the stored user agent is not used. */
    private fun withClearance(stored: WebSessions.Stored?, clearance: Clearance?, domains: List<String>): WebSessions.Stored? {
        clearance ?: return stored
        val cookies = domains.associateWith { domain ->
            SiteCheck.mergeCookies(stored?.cookies?.get(domain), clearance.forHost(domain))
        }.filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }
        return WebSessions.Stored(stored?.userAgent, cookies, stored?.account).takeIf { cookies.isNotEmpty() } ?: stored
    }

    /**
     * Opens [url] (any https page: the plugin's own site, or a file host its page links to) in droidtop's web view with
     * [record]'s session, for the person to start the download there (docs/SPEC.md 7g, "Getting an update";
     * Droidtop/tracker#599): droidtop opens the link the source gave, the person passes whatever the page asks
     * (a masked link, a captcha, the host's own button) and every download the page starts, up to [max], is handed
     * back to droidtop with the cookies, user agent and referrer it needs. The session's cookies go only to the
     * plugin's declared sites; a session the site renewed meanwhile is kept. Nothing when `web.session` is turned off.
     * Blocks until the view closes: never on the main thread.
     */
    fun openForDownloads(context: Context, record: PluginRecord, url: String, max: Int): List<WebSessionDownload> {
        if (!NetScope.isHttps(url) || NetScope.hostOf(url) == null) return emptyList()
        // A link a host account, a debrid account or the host's own API serves comes from there, without the page (#638).
        var refusal: String? = null
        dev.droidtop.pluginhost.hosts.HostLinks.resolve(url, refused = { refusal = it })?.let { return it.take(max) }
        val domains = WebSessions.declaredDomains(record.manifest.v2.permissions)
        val snapshot = PluginGrants.forContext(context).read(record.manifest.id)
        val grant = PluginGrants.stateOf(record, snapshot, WebSessions.PERMISSION)
        val vault = PluginVault.forContext(context)
        // Without the permission the page still opens, signed out: the person may still start a public download.
        val stored = if (grant == GrantState.GRANTED) WebSessions.Stored.parse(vault.getHost(record.manifest.id, WebSessions.VAULT_KEY)) else null
        // droidtop's sign-in for a declared download site the plugin keeps none of its own for (Droidtop/tracker#648).
        val shared = if (grant == GrantState.GRANTED) runCatching { DownloadSites.sharedWith(context, record.manifest.id, domains) }.getOrDefault(emptyMap()) else emptyMap()
        val borrowed = shared.keys.filter { stored?.cookies?.get(it).isNullOrBlank() }
        val inUse = DownloadSites.borrow(stored, shared)
        val request = WebSessionRequest(record.manifest.label, WebSessionRequest.Mode.OPEN, url, if (inUse != null) domains else emptyList(), inUse, null, maxDownloads = max.coerceIn(1, 16), signInUrl = signInUrl(record))
        val result = showKept(context, record, request)
        SiteChecks.keep(vault, record.manifest.id, result.session)
        // A shared site's renewed cookies go back to droidtop, never into the plugin's vault.
        result.session?.cookies?.filterKeys { it in borrowed }?.forEach { (domain, header) -> DownloadSites.renew(context, domain, header) }
        result.session?.let { it.copy(cookies = it.cookies.filterKeys { domain -> domain !in borrowed }) }
            ?.takeIf { stored != null && it.cookies.isNotEmpty() }?.let {
                vault.putHost(record.manifest.id, WebSessions.VAULT_KEY, it.copy(account = stored?.account).toJson())
            }
        // The page led to a host droidtop serves (a masked link the person unmasked, Droidtop/tracker#650): that link
        // goes through the resolvers above, and opens its own page only if none serves it (the view does not hand off
        // the page it was opened on, so this ends there).
        result.handoff?.let { return openForDownloads(context, record, it, max) }
        // A host whose API refused the link (Mega's limit on anonymous downloads) says so in the job's line.
        return result.downloads.take(max).map { download -> refusal?.let { download.copy(via = "from the web page ($it)") } ?: download }
    }

    /** The line shown when [open] kept no session: the person backed out, or the permission is off. */
    fun notKept(pluginLabel: String, need: Need): String = when (need) {
        Need.SIGN_IN -> "Not signed in. If $pluginLabel's sign-in is turned off, turn it on in its Permissions"
        Need.BROWSER_CHECK -> "The site's check was not finished. Open the site again when you are ready"
    }

    /** Forgets [record]'s session (the account row's Sign out; the same as the plugin's own `web.session.clear`). */
    fun signOut(context: Context, record: PluginRecord): Boolean =
        PluginVault.forContext(context).deleteHost(record.manifest.id, WebSessions.VAULT_KEY)

    /**
     * Switch account: the session is forgotten and the declared sign-in page opened in a clean view. Backing out leaves
     * the plugin signed out, which the row then says. Blocks until the view closes: never on the main thread.
     */
    fun switchAccount(context: Context, record: PluginRecord): Boolean {
        signOut(context, record)
        return open(context, record, Need.SIGN_IN)
    }
}

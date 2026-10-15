package dev.droidtop.pluginhost

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
     * The account row's state: [site] is the first declared site; [signInUrl] the declared sign-in page (null when the
     * plugin declared none: droidtop then offers no Sign in of its own); [grant] the `web.session` permission's state.
     */
    data class Account(val site: String, val signInUrl: String?, val signedIn: Boolean, val grant: GrantState) {
        val allowed: Boolean get() = grant != GrantState.DENIED
    }

    /** The `signIn` address of [record]'s `web.session` entry, when it is https and on one of its declared sites. */
    fun signInUrl(record: PluginRecord): String? {
        val permissions = record.manifest.v2.permissions
        val entry = permissions.firstOrNull { it.id == WebSessions.PERMISSION } ?: return null
        val url = runCatching { JSONObject(entry.extra).optString("signIn").trim() }.getOrDefault("")
        val domains = WebSessions.declaredDomains(permissions)
        return url.takeIf { it.isNotEmpty() && WebSessions.domainFor(it, domains) != null }
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
        return Account(site, signInUrl(record), stored?.cookies?.isNotEmpty() == true, grant)
    }

    /**
     * Shows the site's page in the plugin's session for [need] and keeps the session the person ends with Done. Pressing
     * droidtop's Sign in for a plugin is the person allowing what `web.session` means ("Use your sign-in on its sites"),
     * so an unanswered (ASK) grant becomes GRANTED when a session is kept; a DENIED one is never overridden here (the
     * caller offers the plugin's Permissions instead). Blocks until the view closes: never on the main thread.
     * Returns whether a session is now kept.
     */
    fun open(context: Context, record: PluginRecord, need: Need): Boolean {
        val account = of(context, record) ?: return false
        if (!account.allowed) return false
        val url = when (need) {
            Need.SIGN_IN -> account.signInUrl ?: checkUrl(record)
            Need.BROWSER_CHECK -> checkUrl(record)
        } ?: return false
        val domains = WebSessions.declaredDomains(record.manifest.v2.permissions)
        val vault = PluginVault.forContext(context)
        val stored = WebSessions.Stored.parse(vault.getHost(record.manifest.id, WebSessions.VAULT_KEY))
        val mode = if (need == Need.SIGN_IN) WebSessionRequest.Mode.SIGN_IN else WebSessionRequest.Mode.CHECK
        val result = WebSessionActivity.show(context, WebSessionRequest(record.manifest.label, mode, url, domains, stored, null))
        val session = result.session?.takeIf { result.completed && it.cookies.isNotEmpty() } ?: return false
        vault.putHost(record.manifest.id, WebSessions.VAULT_KEY, session.toJson())
        if (account.grant == GrantState.ASK) PluginGrants.forContext(context).set(record.manifest.id, WebSessions.PERMISSION, GrantState.GRANTED)
        return true
    }

    /** The line shown when [open] kept no session: the person backed out, or the permission is off. */
    fun notKept(pluginLabel: String, need: Need): String = when (need) {
        Need.SIGN_IN -> "Not signed in. If $pluginLabel's sign-in is turned off, turn it on in its Permissions"
        Need.BROWSER_CHECK -> "The site's check was not finished. Open the site again when you are ready"
    }

    /** Forgets [record]'s session (the account row's Sign out; the same as the plugin's own `web.session.clear`). */
    fun signOut(context: Context, record: PluginRecord): Boolean =
        PluginVault.forContext(context).deleteHost(record.manifest.id, WebSessions.VAULT_KEY)
}

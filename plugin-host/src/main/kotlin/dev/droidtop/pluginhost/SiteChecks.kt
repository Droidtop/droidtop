package dev.droidtop.pluginhost

import dev.droidtop.net.sitecheck.SiteClearanceStore
import dev.droidtop.net.sitecheck.SiteClearances
import dev.droidtop.shared.sitecheck.Clearance
import java.util.concurrent.ConcurrentHashMap

/**
 * A plugin's side of a site that answers with a browser check (docs/SPEC.md 12a, "A site that checks the browser";
 * Droidtop/tracker#600, #751): the plugin gets one plain `BROWSER_CHECK_REQUIRED` and never the challenge's HTML,
 * droidtop remembers the site waits for a check ([pending]), the person passes it in droidtop's web view, and the
 * clearance the view earned goes with droidtop's requests to that site after. Telling a check from the page, the
 * clearance cookies and the pass test are the shared `SiteCheck` and `Clearance` (droidtop-shared `:sitecheck`),
 * which Enginehost uses too; what is here is what only a plugin has: its vault, and what it was last refused.
 */
object SiteChecks {
    /** The vault entry, host-owned like the session's, that keeps a plugin's clearances. */
    const val VAULT_KEY = "@web.clearance"

    /** The JSON a [Clearance] is kept as in a plugin's vault (the same shape a stored web session has). */
    fun toJson(clearance: Clearance): String = WebSessions.Stored(clearance.userAgent, clearance.cookies).toJson()

    fun parse(text: String?): Clearance? = WebSessions.Stored.parse(text)?.let { Clearance(it.userAgent, it.cookies) }

    /** The clearance a web view left in [session]: its user agent and, per site, only the clearance cookies; null when none. */
    fun clearanceFrom(session: WebSessions.Stored?): Clearance? = Clearance.from(session?.userAgent, session?.cookies.orEmpty())

    /**
     * What droidtop has kept for [pluginId]'s requests: the one clearance store every request of droidtop shares
     * (Droidtop/tracker#751), since a check is the site's and not the plugin's. A clearance an earlier build kept in the
     * plugin's own vault moves into the store the first time it is read, and leaves the vault. Without a store (a test)
     * the vault is the place, as it was.
     */
    fun read(vault: PluginVault, pluginId: String): Clearance? {
        val store = SiteClearances.current ?: return parse(vault.getHost(pluginId, VAULT_KEY))
        if (adopt(vault.getHost(pluginId, VAULT_KEY), store)) vault.deleteHost(pluginId, VAULT_KEY)
        return store.snapshot()
    }

    /** Moves a clearance kept as [legacy] (the vault's JSON) into [store]; whether there was one. */
    fun adopt(legacy: String?, store: SiteClearanceStore): Boolean {
        val kept = parse(legacy) ?: return false
        store.keep(kept)
        return true
    }

    /** Keeps what [session] cleared for [pluginId], over what was kept; nothing when it cleared nothing. */
    fun keep(vault: PluginVault, pluginId: String, session: WebSessions.Stored?) {
        val earned = clearanceFrom(session) ?: return
        val store = SiteClearances.current
        if (store != null) store.keep(earned) else vault.putHost(pluginId, VAULT_KEY, toJson(read(vault, pluginId)?.plus(earned) ?: earned))
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

package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * droidtop's own sign-ins for download sites (docs/SPEC.md 12a "Download sites", Droidtop/tracker#648): a fixed set of
 * file hosts droidtop ships and the sites the person adds, each signed in to once in droidtop's web view. The person
 * signs in on the site's own page; droidtop never types a password. Each site's cookies are sealed in droidtop's vault,
 * one entry per site under [VAULT_ID], which no plugin can name (plugin ids never start with `@`), and never logged.
 * The list of added sites is not secret and is kept beside it.
 */
object DownloadSites {
    /** One site: [domain] covers its subdomains; [signInUrl] is the page the person signs in on. */
    data class Site(val name: String, val domain: String, val signInUrl: String, val shipped: Boolean)

    /** The sites droidtop ships; a shipped site cannot be removed. */
    val SHIPPED = listOf(
        Site("Mega", "mega.nz", "https://mega.nz/login", shipped = true),
        Site("Gofile", "gofile.io", "https://gofile.io/login", shipped = true),
        Site("Pixeldrain", "pixeldrain.com", "https://pixeldrain.com/login", shipped = true),
        Site("MediaFire", "mediafire.com", "https://www.mediafire.com/login/", shipped = true),
        Site("Workupload", "workupload.com", "https://workupload.com/", shipped = true),
    )

    /** The vault namespace the sessions are sealed under: not a plugin id, so no plugin's vault calls reach it. */
    const val VAULT_ID = "@download-sites"

    /** What Add site made of an address: the site, or why not, in one sentence. */
    sealed interface Added {
        data class Site(val site: DownloadSites.Site) : Added
        data class Refused(val reason: String) : Added
    }

    /**
     * The site an address the person typed names (Pure): its host less a leading `www.` is the domain, the domain is its
     * name, and the address is its sign-in page. Refused: anything but https, a domain already on [taken] (or under one),
     * and one a plugin signs in to ([pluginDomains], domain to the plugin's name), which is that plugin's sign-in.
     */
    fun parse(address: String, taken: List<Site>, pluginDomains: Map<String, String>): Added {
        val url = address.trim()
        if (!NetScope.isHttps(url)) return Added.Refused("Type the site's https address, such as https://example.com/login")
        val host = NetScope.hostOf(url)?.lowercase()?.removePrefix("www.")
            ?: return Added.Refused("That is not a site's address")
        if (!host.contains('.')) return Added.Refused("That is not a site's address")
        taken.firstOrNull { WebSessions.covers(host, listOf(it.domain)) || WebSessions.covers(it.domain, listOf(host)) }
            ?.let { return Added.Refused("${it.name} is already on the list") }
        pluginDomains.entries.firstOrNull { (domain, _) -> WebSessions.covers(host, listOf(domain)) || WebSessions.covers(domain, listOf(host)) }
            ?.let { (_, plugin) -> return Added.Refused("$host is the $plugin plugin's sign-in") }
        return Added.Site(Site(host, host, url, shipped = false))
    }

    /** The list file of added sites (not secret): `[{name, domain, signIn}]`. */
    internal fun readAdded(file: File): List<Site> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val domain = o.optString("domain").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val signIn = o.optString("signIn").takeIf { NetScope.isHttps(it) } ?: return@mapNotNull null
            Site(o.optString("name").ifBlank { domain }, domain, signIn, shipped = false)
        }
    }.getOrDefault(emptyList())

    internal fun writeAdded(file: File, sites: List<Site>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(sites.map { JSONObject().put("name", it.name).put("domain", it.domain).put("signIn", it.signInUrl) }).toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun listFile(context: Context) = File(context.applicationContext.noBackupFilesDir, "download-sites.json")

    /** Every site, shipped first, then the added ones in the order they were added. */
    fun all(context: Context): List<Site> = SHIPPED + readAdded(listFile(context))

    /** The sites the installed plugins sign in to themselves, domain to the plugin's name. */
    private fun pluginDomains(context: Context): Map<String, String> =
        PluginStore.installed(context).flatMap { record ->
            WebSessions.declaredDomains(record.manifest.v2.permissions).map { it to record.manifest.label }
        }.toMap()

    /** Add site: the site [address] names, kept, or why not. */
    @Synchronized
    fun add(context: Context, address: String): Added {
        val answer = parse(address, all(context), pluginDomains(context))
        if (answer is Added.Site) writeAdded(listFile(context), readAdded(listFile(context)) + answer.site)
        return answer
    }

    /** Remove: an added site leaves the list, signed out first. A shipped one stays. */
    @Synchronized
    fun remove(context: Context, site: Site): Boolean {
        if (site.shipped) return false
        signOut(context, site)
        val left = readAdded(listFile(context)).filter { it.domain != site.domain }
        writeAdded(listFile(context), left)
        return true
    }

    /** [site]'s kept session, when it has cookies. */
    fun stored(context: Context, site: Site): WebSessions.Stored? =
        WebSessions.Stored.parse(PluginVault.forContext(context).get(VAULT_ID, site.domain))?.takeIf { it.cookies.isNotEmpty() }

    fun signedIn(context: Context, site: Site): Boolean = stored(context, site) != null

    /**
     * Opens [site]'s sign-in page in droidtop's web view; the person signs in on it and presses Done. The cookies of the
     * site's domain are kept, the rest dropped. False when they backed out. Blocks until the view closes: never on the
     * main thread.
     */
    fun signIn(context: Context, site: Site): Boolean {
        val domains = listOf(site.domain)
        val result = WebSessionActivity.show(
            context,
            WebSessionRequest(site.name, WebSessionRequest.Mode.SIGN_IN, site.signInUrl, domains, stored(context, site), null),
        )
        val session = result.session?.takeIf { result.completed }
            ?.let { it.copy(cookies = it.cookies.filterKeys { domain -> WebSessions.covers(domain, domains) }) }
            ?.takeIf { it.cookies.isNotEmpty() }
            ?: return false
        // A session too large for the vault (16 KiB) is not kept, and the row stays signed out.
        return runCatching { PluginVault.forContext(context).put(VAULT_ID, site.domain, session.toJson()) }.isSuccess
    }

    /**
     * The `Cookie` header of every signed-in site, by domain, leaving out the sites [except] covers (a view already
     * carrying that site's session, such as its own sign-in). What droidtop's web view puts in while it is open (slice 2).
     */
    fun cookies(context: Context, except: List<String> = emptyList()): Map<String, String> =
        all(context).filter { !WebSessions.covers(it.domain, except) }.mapNotNull { site ->
            stored(context, site)?.cookies?.get(site.domain)?.takeIf { it.isNotBlank() }?.let { site.domain to it }
        }.toMap()

    /**
     * [headers] for a download of [url], with the `Cookie` of the signed-in site it is on ([cookies], domain to header)
     * when the caller sent none: a captured download already carries the page's, and an https address only. Pure.
     */
    fun withCookie(headers: Map<String, String>, url: String, cookies: Map<String, String>): Map<String, String> {
        if (headers.keys.any { it.equals("Cookie", ignoreCase = true) }) return headers
        val domain = WebSessions.domainFor(url, cookies.keys.toList()) ?: return headers
        return headers + ("Cookie" to cookies.getValue(domain))
    }

    /** The cookies a site renewed while droidtop's view was open, sealed again; nothing for a site not signed in. */
    fun renew(context: Context, domain: String, header: String) {
        if (header.isBlank()) return
        val site = all(context).firstOrNull { it.domain == domain } ?: return
        val kept = stored(context, site) ?: return
        if (kept.cookies[domain] == header) return
        runCatching { PluginVault.forContext(context).put(VAULT_ID, domain, kept.copy(cookies = kept.cookies + (domain to header)).toJson()) }
    }

    /** Sign out: [site]'s cookies are forgotten, and no other site's. */
    fun signOut(context: Context, site: Site): Boolean = PluginVault.forContext(context).delete(VAULT_ID, site.domain)
}

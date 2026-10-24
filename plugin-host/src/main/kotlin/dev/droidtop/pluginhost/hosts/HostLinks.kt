package dev.droidtop.pluginhost.hosts

import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.WebSessionDownload
import dev.droidtop.pluginhost.debrid.DebridLinks
import java.io.IOException

/**
 * A source's download link on a file host, taken without the host's page where something can serve it (docs/SPEC.md
 * 12a "Host resolvers", Droidtop/tracker#638; owner: "free unrestricted premium downloads ... get used first"). In order:
 * the person's own account on the host, then a debrid account by [dev.droidtop.pluginhost.debrid.DebridChoice], then
 * the host's anonymous API, and when none serves it null: droidtop's web view opens the page as before. Asked before
 * the web view opens a link for a download (`PluginWebAccount.openForDownloads`, `web.session open_in_session`).
 * Blocking: called where the web view would block.
 */
object HostLinks {
    /** :app sets this: the person's key for the host with this id ("pixeldrain"), or null when they have none. */
    @Volatile var accounts: (hostId: String) -> String? = { null }

    val resolvers: List<HostResolver> by lazy { listOf(PixelDrain()) }

    /** [link]'s files, or null when no way serves it. [debrid] and [resolvers] are replaceable for the tests. */
    fun resolve(
        link: String,
        debrid: (String) -> List<WebSessionDownload>? = DebridLinks::resolve,
        resolvers: List<HostResolver> = this.resolvers,
    ): List<WebSessionDownload>? {
        val host = resolvers.firstOrNull { it.matches(link) }
        val key = host?.let { accounts(it.id)?.trim()?.takeIf { k -> k.isNotEmpty() } }
        if (host != null && key != null) serve(host, link, key, "via your ${host.name}")?.let { return it }
        debrid(link)?.let { return it }
        return host?.let { serve(it, link, null, "direct from ${it.name}") }
    }

    /** [host]'s files for [link] as downloads, or null when it refuses, cannot be asked, or has more files than one job takes. */
    private fun serve(host: HostResolver, link: String, key: String?, via: String): List<WebSessionDownload>? {
        val files = try {
            (host.resolve(link, key) as? HostResolution.Files)?.files
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
        if (files.isNullOrEmpty() || files.size > DownloadJobs.MAX_FILES) return null
        return files.map { WebSessionDownload.direct(it.url, it.name, it.size, it.headers, it.sha256, via) }
    }
}

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
    /** :app sets this: the person's key for the host with this id ("pixeldrain", "gofile"), or null when they have none. */
    @Volatile var accounts: (hostId: String) -> String? = { null }

    val resolvers: List<HostResolver> by lazy { listOf(PixelDrain(), Mega(), GoFile()) }

    /**
     * Whether [link] is on a host droidtop has an API for, or one a connected debrid account serves (from its host list
     * already fetched, `DebridLinks.prime`), so a page that leads to it hands it here instead of loading the host's page
     * (Droidtop/tracker#650: a masked forum link the person unmasks in the view; #638: with TorBox signed in, a GoFile
     * or MixDrop mirror was never handed to TorBox, because only PixelDrain and Mega counted). Local, no request.
     */
    fun served(
        link: String,
        resolvers: List<HostResolver> = this.resolvers,
        debrid: (String) -> Boolean = DebridLinks::serves,
    ): Boolean = resolvers.any { it.matches(link) } || debrid(link)

    /**
     * [link]'s files, or null when no way serves it. [debrid] and [resolvers] are replaceable for the tests. [refused] is
     * told the host's sentence each time its API refuses the link (a limit, a missing file, a refused key): when the link
     * then comes back null, the web view opens it and the caller can say why.
     */
    fun resolve(
        link: String,
        debrid: ((String) -> List<WebSessionDownload>?)? = null,
        resolvers: List<HostResolver> = this.resolvers,
        refused: (String) -> Unit = {},
    ): List<WebSessionDownload>? {
        val host = resolvers.firstOrNull { it.matches(link) }
        val key = host?.let { accounts(it.id)?.trim()?.takeIf { k -> k.isNotEmpty() } }
        if (host != null && key != null) serve(host, link, key, "via your ${host.name}", refused)?.let { return it }
        // A debrid account passed over says why, as a host's own refusal does (Droidtop/tracker#638).
        (debrid ?: { l -> DebridLinks.resolve(l, refused) })(link)?.let { return it }
        return host?.let { serve(it, link, null, "direct from ${it.name}", refused) }
    }

    /** [host]'s files for [link] as downloads, or null when it refuses, cannot be asked, or has more files than one job takes. */
    private fun serve(host: HostResolver, link: String, key: String?, via: String, refused: (String) -> Unit): List<WebSessionDownload>? {
        val files = try {
            when (val answer = host.resolve(link, key)) {
                is HostResolution.Files -> answer.files
                is HostResolution.Refused -> { refused(answer.reason); null }
            }
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
        if (files.isNullOrEmpty() || files.size > DownloadJobs.MAX_FILES) return null
        return files.map { file ->
            val own = file.decrypt?.let { mapOf(HostDecryption.SECRET to it) }.orEmpty()
            WebSessionDownload.direct(file.url, file.name, file.size, file.headers + own, file.sha256, via, file.md5)
        }
    }
}

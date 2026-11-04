package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.AcquireDownloadDescriptor
import dev.droidtop.pluginhost.WebSessionDownload

/**
 * A source's acquire reply that names a file host's address outright (`download.url`, no page started in the web view)
 * reached the Downloads job as it was, so the person's TorBox or Real-Debrid account, and their own host account, were
 * never asked: only a link droidtop opened in its view went through `HostLinks` (Droidtop/tracker#638; the owner,
 * 2026-11-04: "torbox integration doesn't seem to be working, it downloads everything directly"). Each such address is
 * now offered to the same `HostLinks` first; what serves it replaces the address, and anything else is left as the source
 * gave it.
 */
object HostRoute {
    /** One descriptor after routing, and the line saying what served it ("via your TorBox"), null when left as given. */
    data class Routed(val descriptor: AcquireDownloadDescriptor, val via: String?)

    /**
     * [descriptors], each plain https address that [served] says an account or a host's API takes replaced by the one
     * file [resolve] gives for it. Left as given: a page's capture (its token), a GitHub asset, a torrent, a link that
     * resolves to nothing or to several files (one descriptor is one file). The source's name, digests and unpacking
     * stay. Blocking when [resolve] is: off the main thread.
     */
    fun route(
        descriptors: List<AcquireDownloadDescriptor>,
        served: (String) -> Boolean,
        resolve: (String) -> List<WebSessionDownload>?,
    ): List<Routed> = descriptors.map { d ->
        if (d.session != null || d.auth != null || d.swarm != null || !d.url.startsWith("https://") || !served(d.url)) {
            return@map Routed(d, null)
        }
        val file = resolve(d.url)?.singleOrNull() ?: return@map Routed(d, null)
        val headers = buildMap {
            fun add(name: String, value: String?) {
                value?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }?.let { put(name, it) }
            }
            add("Cookie", file.cookie)
            add("User-Agent", file.userAgent)
            add("Referer", file.referer)
            file.headers.forEach { (name, value) -> add(name, value) }
        }
        Routed(
            d.copy(url = file.url, headers = headers, size = d.size ?: file.size?.takeIf { it > 0 }, sha256 = d.sha256 ?: file.sha256, md5 = d.md5 ?: file.md5),
            file.via,
        )
    }
}

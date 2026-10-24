package dev.droidtop.pluginhost.debrid

import dev.droidtop.pluginhost.NetScope
import dev.droidtop.pluginhost.WebSessionDownload
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * File-hoster links through the person's debrid accounts (Droidtop/tracker#638): a source's download link on a hoster
 * the account lists (Pixeldrain, Mega, 1fichier and so on, asked of the account, never kept by droidtop) comes back as
 * the service's own direct downloads, so the hoster's page, its limits and its captcha are skipped. Asked before
 * droidtop's web view opens a link for a download (`PluginWebAccount.openForDownloads`, `web.session open_in_session`);
 * with no account that serves it, the page opens as before. Blocking: called where the web view would block.
 */
object DebridLinks {
    /** :app sets these: the connected accounts in their order, and whether a link not fetched yet is waited for. */
    @Volatile var accounts: () -> List<Pair<DebridProvider, String>> = { emptyList() }
    @Volatile var waits: () -> Boolean = { false }

    private const val HOSTS_TTL_MS = 60L * 60 * 1000
    private const val POLL_MS = 5_000L
    private const val CACHED_WAIT_MS = 2L * 60 * 1000
    private const val FETCH_WAIT_MS = 10L * 60 * 1000

    private data class Kept(val atMs: Long, val hosts: Set<String>)

    private val hostsKept = ConcurrentHashMap<String, Kept>()

    /** Whether [host] is one of [domains] or below one. Pure. */
    fun covers(domains: Set<String>, host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return domains.any { d -> h == d || h.endsWith(".$d") }
    }

    /** [link]'s files from the first account that serves its hoster, or null when none does (the page opens then). */
    fun resolve(link: String): List<WebSessionDownload>? {
        val host = NetScope.hostOf(link) ?: return null
        val wait = waits()
        for ((provider, key) in DebridChoice.order(accounts(), link = link)) {
            val hosts = hostsOf(provider, key)
            if (!covers(hosts, host)) continue
            val cached = quietly { provider.linkCached(key, link) }
            if (cached == false && !wait) continue
            val ready = settle(provider, key, link, if (cached == true) CACHED_WAIT_MS else FETCH_WAIT_MS) ?: continue
            DebridChoice.spent(provider, key)
            return ready.files.map { file -> WebSessionDownload.direct(file.url, file.path, file.size) }
        }
        return null
    }

    private fun hostsOf(provider: DebridProvider, key: String): Set<String> {
        val id = provider.id + ":" + key.hashCode()
        val now = System.currentTimeMillis()
        hostsKept[id]?.takeIf { now - it.atMs < HOSTS_TTL_MS }?.let { return it.hosts }
        val hosts = quietly { provider.hosts(key) } ?: return emptySet()
        hostsKept[id] = Kept(now, hosts)
        return hosts
    }

    private fun settle(provider: DebridProvider, key: String, link: String, maxWaitMs: Long): DebridState.Ready? {
        var waited = 0L
        while (true) {
            when (val state = quietly { provider.unrestrict(key, link) }) {
                is DebridState.Ready -> return state
                is DebridState.Caching -> {
                    if (waited >= maxWaitMs) return null
                    Thread.sleep(POLL_MS)
                    waited += POLL_MS
                }
                else -> {
                    DebridChoice.refused(provider, key, state)
                    return null
                }
            }
        }
    }

    private fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (e: IOException) {
        null
    } catch (e: org.json.JSONException) {
        null
    }
}

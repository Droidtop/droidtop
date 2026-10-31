package dev.droidtop.pluginhost.debrid

import dev.droidtop.pluginhost.NetScope
import dev.droidtop.pluginhost.WebSessionDownload
import android.util.Log
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

    /**
     * [link]'s files from the first account that serves its hoster, or null when none does (the page opens then).
     * [skipped] is told, in the person's words, each time an account is passed over and why (Droidtop/tracker#638:
     * with TorBox signed in, the console log had no debrid line at all), and every decision is logged under
     * [TAG]. A hoster link an account has not fetched yet is fetched: there is no one else to download it from, so
     * "Wait for the service", which is about torrents, does not apply here.
     */
    fun resolve(link: String, skipped: (String) -> Unit = {}): List<WebSessionDownload>? {
        val host = NetScope.hostOf(link) ?: return null
        val all = accounts()
        if (all.isEmpty()) {
            note("$host: no debrid account connected")
            return null
        }
        val order = DebridChoice.order(all, link = link)
        if (order.isEmpty()) {
            say(skipped, "No debrid account can take a download right now (each is resting or out of its allowance)")
        }
        for ((provider, key) in order) {
            val hosts = hostsOf(provider, key)
            if (hosts.isEmpty()) {
                say(skipped, "${provider.name} didn't say which hosts it serves, so it was skipped for $host")
                continue
            }
            if (!covers(hosts, host)) {
                say(skipped, "${provider.name} doesn't serve $host")
                continue
            }
            val cached = quietly { provider.linkCached(key, link) }
            note("$host: asking ${provider.name} (${if (cached == true) "it has the file" else "it fetches the file first"})")
            val ready = settle(provider, key, link, if (cached == true) CACHED_WAIT_MS else FETCH_WAIT_MS, skipped) ?: continue
            DebridChoice.spent(provider, key)
            note("$host: served by ${provider.name}, ${ready.files.size} file(s)")
            // The job's line says which account served it, as a host account's does ("via your PixelDrain").
            return ready.files.map { file -> WebSessionDownload.direct(file.url, file.path, file.size, via = "via your ${provider.name}") }
        }
        return null
    }

    private fun say(skipped: (String) -> Unit, reason: String) {
        note(reason)
        skipped(reason)
    }

    /**
     * Whether a connected account is known to serve [link]'s host, from the host lists already fetched ([prime]); no
     * request, so the web view can ask it as it navigates (`HostLinks.served`).
     */
    fun serves(link: String): Boolean {
        val host = NetScope.hostOf(link) ?: return false
        return keptFor(accounts()).any { covers(it, host) }
    }

    /**
     * Whether a connected account is known to serve the host a source names ("GOFILE", "Pixeldrain"): a masked forum
     * link carries only that name until it is opened, so droidtop's pick ranks such mirrors by it (Droidtop/tracker#838).
     * From the lists already fetched; no request.
     */
    fun servesName(name: String): Boolean {
        val wanted = name.trim().lowercase().replace(" ", "")
        if (wanted.isEmpty()) return false
        return keptFor(accounts()).any { domains -> domains.any { it.substringBefore('.') == wanted || it.removeSuffix(".com").removeSuffix(".io") == wanted } }
    }

    /** Fetches each connected account's host list that is not fresh. Blocking, a request per account: off the main thread. */
    fun prime() {
        for ((provider, key) in accounts()) hostsOf(provider, key)
    }

    private fun keptFor(accounts: List<Pair<DebridProvider, String>>): List<Set<String>> {
        val now = System.currentTimeMillis()
        return accounts.mapNotNull { (provider, key) -> hostsKept[provider.id + ":" + key.hashCode()]?.takeIf { now - it.atMs < HOSTS_TTL_MS }?.hosts }
    }

    private fun hostsOf(provider: DebridProvider, key: String): Set<String> {
        val id = provider.id + ":" + key.hashCode()
        val now = System.currentTimeMillis()
        hostsKept[id]?.takeIf { now - it.atMs < HOSTS_TTL_MS }?.let { return it.hosts }
        val hosts = quietly { provider.hosts(key) } ?: return emptySet()
        hostsKept[id] = Kept(now, hosts)
        return hosts
    }

    private fun settle(provider: DebridProvider, key: String, link: String, maxWaitMs: Long, skipped: (String) -> Unit): DebridState.Ready? {
        var waited = 0L
        while (true) {
            when (val state = quietly { provider.unrestrict(key, link) }) {
                is DebridState.Ready -> return state
                is DebridState.Caching -> {
                    if (waited >= maxWaitMs) {
                        say(skipped, "${provider.name} hadn't finished fetching it after ${maxWaitMs / 60_000} minutes")
                        return null
                    }
                    Thread.sleep(POLL_MS)
                    waited += POLL_MS
                }
                null -> {
                    say(skipped, "${provider.name} couldn't be reached")
                    return null
                }
                else -> {
                    DebridChoice.refused(provider, key, state)
                    say(skipped, "${provider.name} refused it: ${refusalWords(state)}")
                    return null
                }
            }
        }
    }

    private fun refusalWords(state: DebridState): String = when (state) {
        is DebridState.Failed -> state.message
        else -> state::class.simpleName?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.lowercase() ?: "no reason given"
    }

    private const val TAG = "droidtop.debrid"

    /** One line under [TAG]. Android's log in the app; on a plain JVM (the unit tests) its stub throws, which is ignored. */
    private fun note(line: String) {
        runCatching { Log.i(TAG, line) }
    }

    private fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (e: IOException) {
        null
    } catch (e: org.json.JSONException) {
        null
    }
}

package dev.droidtop.pluginhost.debrid

import org.json.JSONArray
import org.json.JSONObject

/**
 * Real-Debrid (https://real-debrid.com), the flow of romgi's RealDebridProvider for a whole torrent: find the torrent
 * among the account's (adding a magnet is not idempotent there), else add it; select all its files when it waits for a
 * selection; once `downloaded`, unrestrict each of its links into a direct address. The key is the account's private
 * API token (real-debrid.com/apitoken) or a device sign-in's access token, as a bearer token. Real-Debrid no longer
 * says what it has cached without adding it, so [cached] cannot say.
 */
class RealDebrid(
    private val http: DebridTransport = DebridTransport.HTTP,
    private val now: () -> Long = System::currentTimeMillis,
) : DebridProvider {
    override val id = "realdebrid"
    override val name = "Real-Debrid"

    /** Real-Debrid limits the torrents active at once. */
    override val slotLimited = true

    /**
     * From `/user`: an account whose premium ended cannot serve. A link on a hoster Real-Debrid limits spends that
     * hoster's traffic (`/traffic`: `left` of `limit`), and one with none left cannot serve; any other job is free.
     */
    override fun cost(key: String, host: String?): DebridCost {
        val (status, body) = http.call("GET", "$API/user", auth(key), null)
        val user = (jsonOrNull(body) as? JSONObject)?.takeIf { status == 200 }
        if (user != null && user.optString("type") == "free") return DebridCost.NotNow("Real-Debrid premium has ended")
        if (host == null) return DebridCost.Free
        val (listed, traffic) = http.call("GET", "$API/traffic", auth(key), null)
        val byHost = (jsonOrNull(traffic) as? JSONObject)?.takeIf { listed == 200 } ?: return DebridCost.Free
        val h = host.lowercase().removePrefix("www.")
        val entry = byHost.keys().asSequence().firstOrNull { d -> val dl = d.lowercase(); h == dl || h.endsWith(".$dl") }
            ?.let { byHost.optJSONObject(it) } ?: return DebridCost.Free
        val left = (entry.opt("left") as? Number)?.toDouble() ?: return DebridCost.Spends(null)
        val limit = (entry.opt("limit") as? Number)?.toDouble()
        if (left <= 0) return DebridCost.NotNow("Real-Debrid's traffic for $h is used up for today")
        return DebridCost.Spends(limit?.takeIf { it > 0 }?.let { (left / it).coerceIn(0.0, 1.0) })
    }

    private fun auth(key: String) = mapOf("Authorization" to "Bearer ${key.trim()}")

    override fun check(key: String): String? {
        val (status, _) = http.call("GET", "$API/user", auth(key), null)
        return when (status) {
            200 -> null
            401, 403 -> "Real-Debrid did not take that token"
            else -> "Real-Debrid answered $status"
        }
    }

    override fun cached(key: String, torrent: TorrentRef): Boolean? = null

    /** The torrents droidtop added to the person's list this run, by info hash: only these does [release] take off. */
    private val added = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun release(key: String, torrent: TorrentRef) {
        val id = added.remove(torrent.infoHash) ?: return
        http.call("DELETE", "$API/torrents/delete/$id", auth(key), null)
    }

    override fun request(key: String, torrent: TorrentRef): DebridState {
        val id = find(key, torrent.infoHash) ?: run {
            val (status, body) = http.call("POST", "$API/torrents/addMagnet", auth(key), mapOf("magnet" to torrent.magnet))
            refusal(status)?.let { return it }
            ((jsonOrNull(body) as? JSONObject)?.optString("id")?.takeIf { it.isNotBlank() }
                ?: return DebridState.Failed("Real-Debrid did not add the torrent")).also { added[torrent.infoHash] = it }
        }
        val (status, body) = http.call("GET", "$API/torrents/info/$id", auth(key), null)
        refusal(status)?.let { return it }
        if (status == 404) return DebridState.Caching(null) // gone from the account: added again next time
        val info = jsonOrNull(body) as? JSONObject ?: return DebridState.Caching(null)
        val progress = (info.opt("progress") as? Number)?.toDouble()?.div(100.0)
        return when (val state = info.optString("status")) {
            "magnet_conversion", "queued", "downloading", "compressing", "uploading" -> DebridState.Caching(progress)
            "waiting_files_selection" -> {
                val (selected, _) = http.call("POST", "$API/torrents/selectFiles/$id", auth(key), mapOf("files" to "all"))
                refusal(selected)?.let { return it }
                if (selected >= 400 && selected != 404) DebridState.Failed("Real-Debrid did not take the file selection ($selected)", permanent = true)
                else DebridState.Caching(progress)
            }
            "downloaded" -> ready(key, info)
            "magnet_error", "dead" -> DebridState.NotCached
            else -> DebridState.Failed("Real-Debrid says the torrent is \"$state\"", permanent = state == "virus" || state == "error")
        }
    }

    private fun find(key: String, hash: String): String? {
        for (page in 1..3) {
            val (status, body) = http.call("GET", "$API/torrents?limit=100&page=$page", auth(key), null)
            if (status != 200) return null
            val list = jsonOrNull(body) as? JSONArray ?: return null
            list.objects().firstOrNull { it.optString("hash").equals(hash, ignoreCase = true) }?.let { return it.optString("id") }
            if (list.length() < 100) return null
        }
        return null
    }

    /** The selected files and their links (Real-Debrid lists links in the order of the selected files' ids), unrestricted. */
    private fun ready(key: String, info: JSONObject): DebridState {
        val selected = info.optJSONArray("files")?.objects().orEmpty().filter { it.optInt("selected") == 1 }.sortedBy { it.long("id") ?: 0 }
        val links = info.optJSONArray("links")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        if (selected.isEmpty() || links.size != selected.size) return DebridState.NotCached
        val files = selected.zip(links).map { (file, link) ->
            val (status, body) = http.call("POST", "$API/unrestrict/link", auth(key), mapOf("link" to link))
            refusal(status)?.let { return it }
            val answer = jsonOrNull(body) as? JSONObject
            val url = answer?.optString("download")?.takeIf { it.startsWith("https://") } ?: return DebridState.Failed("Real-Debrid gave no download address")
            DebridFile(file.optString("path").trimStart('/'), file.long("bytes"), url)
        }
        return DebridState.Ready(files)
    }

    /**
     * The hosters this account can fetch from now: `hosts/status`, the supported ones not down; `hosts/domains` when
     * Real-Debrid does not answer that. Real-Debrid fetches a hoster link as it is asked, so there is no cache to ask.
     */
    override fun hosts(key: String): Set<String> {
        val (status, body) = http.call("GET", "$API/hosts/status", auth(key), null)
        val byDomain = (jsonOrNull(body) as? JSONObject)?.takeIf { status == 200 }
        if (byDomain != null) {
            return byDomain.keys().asSequence().filter { domain ->
                val host = byDomain.optJSONObject(domain)
                host != null && host.optInt("supported") == 1 && host.optString("status") != "down"
            }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        }
        val (listed, domains) = http.call("GET", "$API/hosts/domains", emptyMap(), null)
        val array = (jsonOrNull(domains) as? JSONArray)?.takeIf { listed == 200 } ?: return emptySet()
        return (0 until array.length()).map { array.optString(it).trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** `unrestrict/link`: the hoster's file as one direct download, or why not. */
    override fun unrestrict(key: String, link: String): DebridState {
        val (status, body) = http.call("POST", "$API/unrestrict/link", auth(key), mapOf("link" to link))
        val answer = jsonOrNull(body) as? JSONObject
        if (status in 200..299) {
            val url = answer?.optString("download")?.takeIf { it.startsWith("https://") } ?: return DebridState.Failed("Real-Debrid gave no download address")
            val name = answer.optString("filename").takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').substringBefore('?')
            return DebridState.Ready(listOf(DebridFile(name, answer.long("filesize")?.takeIf { it > 0 }, url)))
        }
        return errorState(status, answer)
    }

    /** Real-Debrid's `{error, error_code}` as a state: the codes its documentation lists, else by status. Pure, for the tests. */
    internal fun errorState(status: Int, answer: JSONObject?): DebridState.Failed {
        val code = answer?.opt("error_code") as? Number
        val said = answer?.optString("error")?.takeIf { it.isNotBlank() }?.replace('_', ' ')
        return when (code?.toInt()) {
            8 -> DebridState.Failed("Real-Debrid did not take the token", auth = true)
            16 -> DebridState.Failed("Real-Debrid does not support that hoster", permanent = true)
            18 -> DebridState.Failed("Real-Debrid has reached its limit on that hoster for now", limit = true, restUntilMs = now() + HOUR)
            21 -> DebridState.Failed("Real-Debrid has too many of this account's downloads running", limit = true, restUntilMs = now() + 10 * MINUTE)
            23 -> DebridState.Failed("This Real-Debrid account's traffic is used up", limit = true, restUntilMs = now() + DAY)
            34 -> DebridState.Failed("Real-Debrid asked to wait: too many requests", limit = true, restUntilMs = now() + MINUTE)
            35 -> DebridState.Failed("Real-Debrid refuses that file", permanent = true)
            36 -> DebridState.Failed("This Real-Debrid account reached its fair-usage limit", limit = true, restUntilMs = now() + DAY)
            else -> refusal(status) ?: DebridState.Failed("Real-Debrid did not fetch the link (${said ?: "answered $status"})")
        }
    }

    /** A refused answer as one sentence, or null when it was taken. Pure, for the tests. */
    internal fun refusal(status: Int): DebridState.Failed? = when (status) {
        401, 403 -> DebridState.Failed("Real-Debrid did not take the token", auth = true)
        429 -> DebridState.Failed("Real-Debrid asked to wait: too many requests")
        else -> null
    }

    private companion object {
        const val API = "https://api.real-debrid.com/rest/1.0"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}

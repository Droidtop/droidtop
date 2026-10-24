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
class RealDebrid(private val http: DebridTransport = DebridTransport.HTTP) : DebridProvider {
    override val id = "realdebrid"
    override val name = "Real-Debrid"

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

    /** A refused answer as one sentence, or null when it was taken. Pure, for the tests. */
    internal fun refusal(status: Int): DebridState.Failed? = when (status) {
        401, 403 -> DebridState.Failed("Real-Debrid did not take the token", auth = true)
        429 -> DebridState.Failed("Real-Debrid asked to wait: too many requests")
        else -> null
    }

    private companion object {
        const val API = "https://api.real-debrid.com/rest/1.0"
    }
}

package dev.droidtop.pluginhost.debrid

import dev.droidtop.pluginhost.NetScope
import java.net.URLEncoder
import org.json.JSONObject

/**
 * TorBox (https://torbox.app), the flow of romgi's TorboxProvider for a whole torrent: create-or-get it from its magnet
 * (TorBox keeps one per info hash, so asking again is safe), read its state from `mylist`, and once it is finished ask
 * `requestdl` for each file's direct address (valid for some hours). The key is the account's API key, as a bearer
 * token. Every answer is `{success, error, detail, data}`, a refusal `success: false` even on a 200.
 */
class TorBox(
    private val http: DebridTransport = DebridTransport.HTTP,
    private val now: () -> Long = System::currentTimeMillis,
) : DebridProvider {
    override val id = "torbox"
    override val name = "TorBox"

    /** TorBox limits torrents and downloads at once, by plan. */
    override val slotLimited = true

    /**
     * From `user/me`: a paid plan that has not ended spends nothing; a free plan (`plan` 0) spends its monthly
     * downloads, and its `cooldown_until` is a wait. A cooldown shown on a paid plan is not taken as one: TorBox says
     * paid plans are not held by it, and a real one is refused as `COOLDOWN_LIMIT`.
     */
    override fun cost(key: String, link: String?): DebridCost {
        val (status, body) = http.call("GET", "$API/user/me", auth(key), null)
        if (status == 401 || status == 403) return DebridCost.NotNow(KEY_REFUSED, auth = true)
        val data = (jsonOrNull(body) as? JSONObject)?.optJSONObject("data")?.takeIf { status == 200 } ?: return DebridCost.Free
        // A cooldown counts only while it is ahead: a paid plan shows a past one (owner's account, 2026-10-24).
        val until = isoMs(data.optString("cooldown_until"))
        if (until != null && until > now()) return DebridCost.NotNow("TorBox has the account waiting", until)
        val plan = data.optInt("plan", -1)
        if (plan != 0) {
            val ends = isoMs(data.optString("premium_expires_at"))
            if (ends != null && ends <= now()) return DebridCost.NotNow("The TorBox plan has ended")
        }
        if (link != null) return hosterCost(key, link)
        return if (plan == 0) DebridCost.Spends(null) else DebridCost.Free
    }

    /**
     * A hoster link's cost from the account's own `webdl/hosters`, TorBox's live daily use (read on the owner's account,
     * 2026-10-24): the hoster is matched by its `regex` or `domains`; one `down` cannot serve; one capped a day
     * (`daily_link_limit` against `daily_link_used`, `daily_bandwidth_limit` against `daily_bandwidth_used`, a limit of
     * 0 being none) spends the least that is left of its caps, none left being not now; an uncapped one is free.
     */
    private fun hosterCost(key: String, link: String): DebridCost {
        val (status, body) = http.call("GET", "$API/webdl/hosters", auth(key), null)
        if (status != 200) return DebridCost.Free
        val list = (jsonOrNull(body) as? JSONObject)?.optJSONArray("data")?.objects().orEmpty()
        val hoster = hosterFor(list, link) ?: return DebridCost.Free
        val name = hoster.optString("name").ifBlank { NetScope.hostOf(link).orEmpty() }
        if (!isUp(hoster)) return DebridCost.NotNow("TorBox says $name is down")
        val left = listOf("daily_link", "daily_bandwidth").mapNotNull { cap ->
            val limit = (hoster.opt("${cap}_limit") as? Number)?.toDouble()?.takeIf { it > 0 } ?: return@mapNotNull null
            val used = (hoster.opt("${cap}_used") as? Number)?.toDouble() ?: 0.0
            ((limit - used) / limit).coerceIn(0.0, 1.0)
        }.minOrNull() ?: return DebridCost.Free
        return if (left <= 0.0) DebridCost.NotNow("TorBox's daily limit on $name is used up") else DebridCost.Spends(left)
    }

    /** The hoster [link] is on: by its `regex`, else its `domains`. Pure, for the tests. */
    internal fun hosterFor(list: List<JSONObject>, link: String): JSONObject? {
        val host = NetScope.hostOf(link) ?: return null
        return list.firstOrNull { entry ->
            entry.optString("regex").takeIf { it.isNotBlank() }?.let { pattern -> runCatching { Regex(pattern).containsMatchIn(link) }.getOrDefault(false) } == true
        } ?: list.firstOrNull { entry ->
            val domains = entry.optJSONArray("domains")?.let { array -> (0 until array.length()).map { array.optString(it).trim().lowercase() } }.orEmpty()
            DebridLinks.covers(domains.toSet(), host)
        }
    }

    /** A hoster TorBox marks `down` (or `false`, as older answers did) is left out. */
    private fun isUp(hoster: JSONObject): Boolean = when (val status = hoster.opt("status")) {
        false -> false
        is String -> !status.equals("down", ignoreCase = true)
        else -> true
    }

    private fun auth(key: String) = mapOf("Authorization" to "Bearer ${key.trim()}")

    override fun check(key: String): String? {
        val (status, _) = http.call("GET", "$API/user/me", auth(key), null)
        return when (status) {
            200 -> null
            401, 403 -> KEY_REFUSED
            else -> "TorBox answered $status"
        }
    }

    override fun cached(key: String, torrent: TorrentRef): Boolean? {
        val (status, body) = http.call("GET", "$API/torrents/checkcached?hash=${torrent.infoHash}&format=object", auth(key), null)
        if (status != 200) return null
        val data = (jsonOrNull(body) as? JSONObject)?.opt("data")
        return when (data) {
            is JSONObject -> data.length() > 0
            null, JSONObject.NULL -> false
            else -> null
        }
    }

    override fun request(key: String, torrent: TorrentRef): DebridState {
        val (status, body) = http.call("POST", "$API/torrents/createtorrent", auth(key), mapOf("magnet" to torrent.magnet, "allow_zip" to "false", "seed" to NO_SEEDING))
        refusal(status, body)?.let { return it }
        val id = ((jsonOrNull(body) as? JSONObject)?.optJSONObject("data"))?.long("torrent_id")
        val listed = if (id != null) torrentById(key, id) else torrentByHash(key, torrent.infoHash)
        val item = listed ?: return DebridState.Caching(null) // queued, not listed yet
        val torrentId = id ?: item.long("id") ?: return DebridState.Caching(null)
        if (!(item.optBoolean("download_present") && item.optBoolean("download_finished"))) {
            return DebridState.Caching((item.opt("progress") as? Number)?.toDouble())
        }
        val files = item.optJSONArray("files")?.objects().orEmpty()
        if (files.isEmpty()) return DebridState.NotCached
        val ready = files.map { file ->
            val fileId = file.long("id") ?: return DebridState.Failed("TorBox listed a file without an id")
            val ask = "$API/torrents/requestdl?token=${enc(key.trim())}&torrent_id=$torrentId&file_id=$fileId"
            val (dlStatus, dlBody) = http.call("GET", ask, auth(key), null)
            refusal(dlStatus, dlBody)?.let { return it }
            val url = (jsonOrNull(dlBody) as? JSONObject)?.optString("data")?.takeIf { it.startsWith("https://") }
                ?: return DebridState.Failed("TorBox gave no download address")
            DebridFile(file.optString("name").ifBlank { file.optString("short_name") }, file.long("size"), url)
        }
        return DebridState.Ready(ready)
    }

    /** `webdl/hosters`: each hoster's `domains`, of those whose `status` is not false (a hoster down for now). */
    override fun hosts(key: String): Set<String> {
        val (status, body) = http.call("GET", "$API/webdl/hosters", auth(key), null)
        if (status != 200) return emptySet()
        val list = (jsonOrNull(body) as? JSONObject)?.optJSONArray("data")?.objects().orEmpty()
        return list.filter(::isUp).flatMap { hoster ->
            hoster.optJSONArray("domains")?.let { domains -> (0 until domains.length()).map { domains.optString(it).trim().lowercase() } }.orEmpty()
        }.filter { it.isNotEmpty() }.toSet()
    }

    /** `webdl/checkcached` by the link's MD5. */
    override fun linkCached(key: String, link: String): Boolean? {
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }
        val (status, body) = http.call("GET", "$API/webdl/checkcached?hash=$md5&format=object", auth(key), null)
        if (status != 200) return null
        return when (val data = (jsonOrNull(body) as? JSONObject)?.opt("data")) {
            is JSONObject -> data.length() > 0
            null, JSONObject.NULL -> false
            else -> null
        }
    }

    /** `webdl/createwebdownload` (`webdownload_id`), its state from `webdl/mylist`, then `webdl/requestdl` per file. */
    override fun unrestrict(key: String, link: String): DebridState {
        val (status, body) = http.call("POST", "$API/webdl/createwebdownload", auth(key), mapOf("link" to link))
        refusal(status, body)?.let { return it }
        val id = (jsonOrNull(body) as? JSONObject)?.optJSONObject("data")?.opt("webdownload_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: return DebridState.Caching(null)
        val (listed, listBody) = http.call("GET", "$API/webdl/mylist?id=${enc(id)}&bypass_cache=true", auth(key), null)
        if (listed != 200) return DebridState.Caching(null)
        val item = when (val data = (jsonOrNull(listBody) as? JSONObject)?.opt("data")) {
            is JSONObject -> data
            is org.json.JSONArray -> data.optJSONObject(0)
            else -> null
        } ?: return DebridState.Caching(null)
        if (!(item.optBoolean("download_present") && item.optBoolean("download_finished"))) {
            return DebridState.Caching((item.opt("progress") as? Number)?.toDouble())
        }
        val files = item.optJSONArray("files")?.objects().orEmpty()
        if (files.isEmpty()) return DebridState.NotCached
        return DebridState.Ready(
            files.map { file ->
                val fileId = file.opt("id")?.toString() ?: return DebridState.Failed("TorBox listed a file without an id")
                val ask = "$API/webdl/requestdl?token=${enc(key.trim())}&web_id=${enc(id)}&file_id=${enc(fileId)}"
                val (dlStatus, dlBody) = http.call("GET", ask, auth(key), null)
                refusal(dlStatus, dlBody)?.let { return it }
                // The address is `data` itself; an object with `download_link` is read too.
                val data = (jsonOrNull(dlBody) as? JSONObject)?.opt("data")
                val url = ((data as? JSONObject)?.optString("download_link") ?: data as? String)?.takeIf { it.startsWith("https://") }
                    ?: return DebridState.Failed("TorBox gave no download address")
                DebridFile(file.optString("short_name").ifBlank { file.optString("name").substringAfterLast('/') }, file.long("size"), url)
            },
        )
    }

    private fun torrentById(key: String, id: Long): JSONObject? {
        val (status, body) = http.call("GET", "$API/torrents/mylist?id=$id&bypass_cache=true", auth(key), null)
        if (status != 200) return null
        return when (val data = (jsonOrNull(body) as? JSONObject)?.opt("data")) {
            is JSONObject -> data
            is org.json.JSONArray -> data.optJSONObject(0)
            else -> null
        }
    }

    private fun torrentByHash(key: String, hash: String): JSONObject? {
        val (status, body) = http.call("GET", "$API/torrents/mylist?bypass_cache=true", auth(key), null)
        if (status != 200) return null
        return (jsonOrNull(body) as? JSONObject)?.optJSONArray("data")?.objects()?.firstOrNull { it.optString("hash").equals(hash, ignoreCase = true) }
    }

    /** A refused answer as one sentence, or null when it was taken. Pure, for the tests. */
    internal fun refusal(status: Int, body: String): DebridState.Failed? {
        if (status == 401 || status == 403) return DebridState.Failed(KEY_REFUSED, auth = true)
        if (status == 429) return DebridState.Failed("TorBox asked to wait: too many requests")
        val json = jsonOrNull(body) as? JSONObject
        if (status >= 400 || json?.optBoolean("success", true) == false) {
            val code = json?.optString("error").orEmpty()
            val detail = json?.optString("detail").orEmpty().ifBlank { code.ifBlank { "the request failed ($status)" } }
            val rest = LIMIT_CODES[code]
            return DebridState.Failed(
                if (code in AUTH_CODES) KEY_REFUSED else "TorBox: $detail",
                auth = code in AUTH_CODES,
                permanent = code in PERMANENT_CODES,
                limit = rest != null,
                restUntilMs = rest?.let { now() + it },
            )
        }
        return null
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    internal companion object {
        const val API = "https://api.torbox.app/v1/api"

        /**
         * `seed` 3, "don't seed" (TorBox's CreateTorrentRequest: 1 auto, 2 seed, 3 don't seed; it overrides the account's
         * setting for this torrent): a seeding torrent counts as active on TorBox (owner, 2026-10-24), and droidtop never seeds.
         */
        const val NO_SEEDING = "3"

        /** A refused key: TorBox rotates the key after an abuse warning, so the person enters the new one. */
        const val KEY_REFUSED = "TorBox refused the API key (it changes the key after an abuse warning): enter the new one"
        val AUTH_CODES = setOf("AUTH_ERROR", "BAD_TOKEN", "OAUTH_VERIFICATION_ERROR", "NO_AUTH")
        /** The refusals of a limit, and how long the account rests after one (a cooldown's end comes from `user/me`). */
        val LIMIT_CODES = mapOf(
            "ACTIVE_LIMIT" to 10L * 60 * 1000,
            "COOLDOWN_LIMIT" to 60L * 60 * 1000,
            "MONTHLY_LIMIT" to 24L * 60 * 60 * 1000,
        )
        val PERMANENT_CODES = setOf(
            "DOWNLOAD_TOO_LARGE", "INVALID_MAGNET", "INVALID_TORRENT",
            "PLAN_RESTRICTED_FEATURE", "MISSING_REQUIRED_OPTION", "INVALID_OPTION",
        )
    }
}

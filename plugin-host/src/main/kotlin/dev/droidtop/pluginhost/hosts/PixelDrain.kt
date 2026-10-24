package dev.droidtop.pluginhost.hosts

import dev.droidtop.pluginhost.debrid.DebridTransport
import dev.droidtop.pluginhost.debrid.jsonOrNull
import dev.droidtop.pluginhost.debrid.long
import dev.droidtop.pluginhost.debrid.objects
import java.net.URI
import org.json.JSONObject

/**
 * PixelDrain (https://pixeldrain.com/api): a file link `/u/<id>` and a list link `/l/<id>`, on pixeldrain.com or
 * pixeldrain.dev. A file's `info` and a list's files say what they are and whether the download is restricted
 * (`availability`), and the file itself is `/api/file/<id>?download`. The person's premium account is their API key
 * as the password of HTTP Basic with an empty user name; without one the download is anonymous. A rate limit, a
 * captcha or a refused key is a [HostResolution.Refused]: nothing is solved here, the link goes on to the web view.
 */
class PixelDrain(private val http: DebridTransport = DebridTransport.HTTP) : HostResolver {
    override val id = "pixeldrain"
    override val name = "PixelDrain"

    private data class Link(val domain: String, val list: Boolean, val id: String)

    private fun parse(url: String): Link? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in DOMAINS) return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0] !in setOf("u", "l") || !ID.matches(parts[1])) return null
        return Link(host, list = parts[0] == "l", id = parts[1])
    }

    override fun matches(url: String): Boolean = parse(url) != null

    override fun resolve(url: String, account: String?): HostResolution {
        val link = parse(url) ?: return HostResolution.Refused("That is not a PixelDrain file or list link")
        val api = "https://${link.domain}/api"
        val auth = auth(account)
        return if (link.list) list(api, link.id, auth) else file(api, link.id, auth)
    }

    private fun file(api: String, id: String, auth: Map<String, String>): HostResolution {
        val (status, body) = http.call("GET", "$api/file/$id/info", auth, null)
        val info = succeeded(status, body) ?: return refusal(status, body, auth.isNotEmpty())
        restricted(info)?.let { return HostResolution.Refused(it) }
        return HostResolution.Files(listOf(fileOf(api, info, auth)))
    }

    private fun list(api: String, id: String, auth: Map<String, String>): HostResolution {
        val (status, body) = http.call("GET", "$api/list/$id", auth, null)
        val reply = succeeded(status, body) ?: return refusal(status, body, auth.isNotEmpty())
        val entries = reply.optJSONArray("files")?.objects().orEmpty().filter { it.optString("id").isNotBlank() }
        if (entries.isEmpty()) return HostResolution.Refused("The PixelDrain list has no files")
        // Part of a game is no use: one file the host restricts keeps the whole list from this way.
        entries.firstNotNullOfOrNull { restricted(it) }?.let { return HostResolution.Refused(it) }
        return HostResolution.Files(entries.map { fileOf(api, it, auth) })
    }

    /** The reply when PixelDrain says it succeeded, else null. */
    private fun succeeded(status: Int, body: String): JSONObject? =
        (jsonOrNull(body) as? JSONObject)?.takeIf { status == 200 && it.optBoolean("success", true) }

    /** Why PixelDrain did not serve: its stable error code (`value`) when it gave one, never its prose. */
    private fun refusal(status: Int, body: String, withKey: Boolean): HostResolution.Refused {
        val value = ((jsonOrNull(body) as? JSONObject)?.opt("value") as? String).orEmpty()
        return HostResolution.Refused(
            when {
                status == 401 && withKey -> "PixelDrain did not take that API key"
                status == 404 -> "PixelDrain does not have that file"
                value.isNotEmpty() -> "PixelDrain will not serve it now ($value)"
                else -> "PixelDrain answered $status"
            },
        )
    }

    /** The error code when the host restricts this file for the one asking (`availability`: a rate limit, a captcha), else null. */
    private fun restricted(info: JSONObject): String? =
        (info.opt("availability") as? String)?.takeIf { it.isNotEmpty() }?.let { "PixelDrain will not serve it now ($it)" }

    private fun fileOf(api: String, info: JSONObject, auth: Map<String, String>): HostFile {
        val id = info.optString("id")
        val sha = (info.opt("hash_sha256") as? String)?.lowercase()?.takeIf { SHA256.matches(it) }
        return HostFile(
            name = (info.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: id,
            size = info.long("size")?.takeIf { it > 0 },
            url = "$api/file/$id?download",
            headers = auth,
            sha256 = sha,
        )
    }

    /**
     * The key is checked on the account itself (`/api/user`): a file's `info` is public and says nothing of the key.
     * Null when it works.
     */
    override fun check(account: String): String? {
        val (status, _) = http.call("GET", "https://pixeldrain.com/api/user", auth(account), null)
        return when (status) {
            200 -> null
            401, 403 -> "PixelDrain did not take that API key"
            else -> "PixelDrain answered $status"
        }
    }

    private companion object {
        val DOMAINS = setOf("pixeldrain.com", "pixeldrain.dev")
        val ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SHA256 = Regex("[0-9a-f]{64}")

        /** HTTP Basic with an empty user name and the key as the password; nothing without a key. */
        fun auth(account: String?): Map<String, String> {
            val key = account?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
            val token = java.util.Base64.getEncoder().encodeToString(":$key".toByteArray(Charsets.UTF_8))
            return mapOf("Authorization" to "Basic $token")
        }
    }
}

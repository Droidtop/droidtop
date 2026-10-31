package dev.droidtop.pluginhost.hosts

import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.debrid.DebridTransport
import dev.droidtop.pluginhost.debrid.jsonOrNull
import dev.droidtop.pluginhost.debrid.long
import dev.droidtop.pluginhost.debrid.objects
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * GoFile (https://gofile.io/api): a link `gofile.io/d/<contentId>`, the id a share code or a UUID. The token is the
 * person's own API token (a bearer), else a guest account's (`POST /accounts`, one per run). `GET /contents/<id>` lists a
 * folder with its `children` (name, size, md5, a direct `link`; folders nest) and each file comes down from its `link`
 * with the cookie `accountToken=<token>`.
 *
 * GoFile's published API says listings are Premium-only (a guest or free token gets `error-notPremium`, checked
 * 2026-10-24 on a guest token), and its own page sends a rotating `X-Website-Token` made by an obfuscated script. Nothing
 * here copies or runs that script: [websiteToken] is the one place such a token would come from, and with none a guest
 * is not asked at all. A refusal of any kind is a [HostResolution.Refused] and the link goes on to debrid or the web view.
 */
class GoFile(
    private val http: DebridTransport = DebridTransport.HTTP,
    /** The `X-Website-Token` for a request made with an account token, or null when there is none (the default). */
    private val websiteToken: (accountToken: String) -> String? = { null },
) : HostResolver {
    override val id = "gofile"
    override val name = "GoFile"

    /** The guest account's token, minted when first needed and kept for the run: GoFile asks for one account, reused. */
    @Volatile private var guest: String? = null

    /** A reason the listing cannot be served, carried out of the walk of a folder. */
    private class Declined(val reason: String) : Exception(reason)

    override fun matches(url: String): Boolean = contentId(url) != null

    private fun contentId(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.host?.lowercase()?.removePrefix("www.") != "gofile.io") return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return parts.takeIf { it.size == 2 && it[0] == "d" && ID.matches(it[1]) }?.get(1)
    }

    override fun resolve(url: String, account: String?): HostResolution {
        val id = contentId(url) ?: return HostResolution.Refused("That is not a GoFile link")
        return try {
            val own = account?.trim()?.takeIf { it.isNotEmpty() }
            if (own != null && !TOKEN.matches(own)) return HostResolution.Refused("GoFile did not take that API token")
            val token = own ?: guestToken()
            val headers = headers(token)
            if (own == null && WEBSITE_TOKEN !in headers) {
                return HostResolution.Refused("GoFile lists a folder for a guest only to its own page")
            }
            val walk = Walk(token, headers)
            walk.add(fetch(id, token, headers))
            // GoFile keys children by id, so the order is the name's: the parts of a split archive come in order.
            val files = walk.files.sortedBy { it.name.lowercase() }
            if (files.isEmpty()) return HostResolution.Refused("The GoFile link has no files")
            val names = files.map { it.name.lowercase() }
            if (names.size != names.toSet().size) {
                return HostResolution.Refused("Two files in the GoFile folder share a name")
            }
            HostResolution.Files(files)
        } catch (e: Declined) {
            HostResolution.Refused(e.reason)
        }
    }

    /** The guest account's token (`data.token` of `POST /accounts`), made once. */
    private fun guestToken(): String {
        guest?.let { return it }
        val (status, body) = http.call("POST", "$API/accounts", emptyMap(), emptyMap())
        val token = reply(status, body)?.optJSONObject("data")?.optString("token")?.takeIf { TOKEN.matches(it) }
            ?: throw Declined("GoFile would not make a guest account")
        guest = token
        return token
    }

    private fun headers(token: String): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        websiteToken(token)?.takeIf { it.isNotBlank() }?.let { put(WEBSITE_TOKEN, it) }
    }

    /** `data` of `GET /contents/<id>`, or a [Declined] saying why GoFile did not serve it. */
    private fun fetch(id: String, token: String, headers: Map<String, String>): JSONObject {
        val (status, body) = http.call("GET", "$API/contents/$id?maxdepth=$MAX_DEPTH", headers, null)
        val data = reply(status, body)?.optJSONObject("data")
        if (data == null) {
            if (status == 401 && guest == token) guest = null
            throw Declined(refusal(status, body))
        }
        if (!data.optBoolean("canAccess", true)) throw Declined("GoFile keeps that link behind a password or has expired it")
        return data
    }

    /** The reply when GoFile says `ok` on a 200, else null. */
    private fun reply(status: Int, body: String): JSONObject? =
        (jsonOrNull(body) as? JSONObject)?.takeIf { status == 200 && it.optString("status") == "ok" }

    /** Why GoFile did not serve: its stable `status` code when it gave one, never its prose. */
    private fun refusal(status: Int, body: String): String {
        val code = (jsonOrNull(body) as? JSONObject)?.optString("status").orEmpty()
        return when {
            code == "error-notPremium" -> "GoFile lists folders through its API for Premium accounts only"
            code == "error-wrongToken" || code == "error-token" -> "GoFile did not take that API token"
            code == "error-notFound" || status == 404 -> "GoFile does not have that content"
            code.isNotEmpty() -> "GoFile will not serve it now ($code)"
            else -> "GoFile answered $status"
        }
    }

    /** One listing's walk: the files found so far and the folders asked for, which are few (a game is not a tree of thousands). */
    private inner class Walk(val token: String, val headers: Map<String, String>) {
        val files = mutableListOf<HostFile>()
        private var asked = 0

        /** Adds [node]'s files; a folder that came without its children is asked for, up to [MAX_FOLDER_CALLS] times. */
        fun add(node: JSONObject) {
            if (files.size > DownloadJobs.MAX_FILES) throw Declined("The GoFile link has more files than one job takes")
            if (node.optString("type") == "file") {
                files += fileOf(node, token)
                return
            }
            when (val children = node.opt("children")) {
                is JSONObject -> children.keys().asSequence().mapNotNull { children.optJSONObject(it) }.toList().forEach(::add)
                is JSONArray -> children.objects().forEach(::add)
                else -> if (node.optInt("childrenCount") > 0) {
                    if (++asked > MAX_FOLDER_CALLS) throw Declined("The GoFile folder nests deeper than droidtop follows")
                    add(fetch(node.optString("id"), token, headers))
                }
            }
        }
    }

    private fun fileOf(node: JSONObject, token: String): HostFile {
        val id = node.optString("id")
        val name = (node.opt("name") as? String)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: id
        val link = (node.opt("link") as? String)?.takeIf { downloadable(it) }
            ?: throw Declined("GoFile gave no download address for $name")
        return HostFile(
            name = name,
            size = node.long("size")?.takeIf { it > 0 },
            url = link,
            headers = mapOf("Cookie" to "accountToken=$token"),
            md5 = (node.opt("md5") as? String)?.lowercase()?.takeIf { MD5.matches(it) },
        )
    }

    /** The account's cookie goes only to GoFile's own hosts, over https. */
    private fun downloadable(link: String): Boolean {
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        return "https".equals(uri.scheme, ignoreCase = true) && uri.host?.lowercase()?.let { GOFILE_HOST.matches(it) } == true
    }

    /** The token must work: `GET /accounts/getid` names the account it belongs to. Null when it does. */
    override fun check(account: String): String? {
        val token = account.trim()
        if (!TOKEN.matches(token)) return "GoFile did not take that API token"
        val (status, body) = http.call("GET", "$API/accounts/getid", mapOf("Authorization" to "Bearer $token"), null)
        val data = reply(status, body)?.optJSONObject("data") ?: return refusal(status, body)
        // Listings are Premium-only (see the class comment); a guest or free token works but would never serve a link.
        return if (data.optString("tier") in FREE_TIERS) "That GoFile account is not Premium, so GoFile will not list folders for it" else null
    }

    private companion object {
        const val API = "https://api.gofile.io"
        const val WEBSITE_TOKEN = "X-Website-Token"

        /** The nesting asked of GoFile in one call (`maxdepth`, 1 to 16), and the folders asked for again past it. */
        const val MAX_DEPTH = 16
        const val MAX_FOLDER_CALLS = 16
        val ID = Regex("[A-Za-z0-9-]{1,64}")
        val TOKEN = Regex("[A-Za-z0-9._~+=-]{8,512}")
        val MD5 = Regex("[0-9a-f]{32}")
        val GOFILE_HOST = Regex("([a-z0-9-]+\\.)*gofile\\.(io|net)")
        val FREE_TIERS = setOf("guest", "standard")
    }
}

package dev.droidtop.pluginhost

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import org.json.JSONObject

/**
 * Which network permission reaching a URL takes (docs/plugin-api.md 3 D2, 4.1): `net.local` for a device on the local
 * network (a private, loopback or link-local address, or a `.local`-style name), `net.domains` for a host the plugin
 * declared, and `net.any` for anything else. The decision is made from the URL alone, before any name is looked up, so
 * a plugin can never make droidtop send a DNS query for a host it may not reach.
 */
object NetScope {
    const val DOMAINS = "net.domains"
    const val ANY = "net.any"
    const val LOCAL = "net.local"

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** The lowercase host of an http(s) [url], or null when it is not one. */
    fun hostOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun isHttps(url: String): Boolean = runCatching { URI(url).scheme.equals("https", ignoreCase = true) }.getOrDefault(false)

    /** The domains a plugin's `net.domains` entry lists; `*.example.com` covers the subdomains of example.com. */
    fun declaredDomains(declared: List<DeclaredPermission>): List<String> {
        val entry = declared.firstOrNull { it.id == DOMAINS } ?: return emptyList()
        val list = runCatching { JSONObject(entry.extra).optJSONArray("domains") }.getOrNull() ?: return emptyList()
        return List(list.length()) { list.optString(it).trim().lowercase() }.filter { it.isNotEmpty() }
    }

    fun matches(host: String, domain: String): Boolean =
        if (domain.startsWith("*.")) host.endsWith(domain.substring(1)) && host.length > domain.length - 1 else host == domain

    /** True for a host that names the local network by itself: loopback, a private or link-local literal, or a local-only name. */
    fun isLocalName(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        if (!host.contains('.') && !host.contains(':')) return true
        val literal = host.matches(IPV4) || host.contains(':')
        return literal && runCatching { isLocalAddress(InetAddress.getByName(host)) }.getOrDefault(true)
    }

    /** Loopback, private (RFC 1918), link-local, unique-local IPv6, carrier-grade NAT and unspecified addresses. */
    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return true
        val bytes = address.address
        if (address is Inet6Address) return (bytes[0].toInt() and 0xfe) == 0xfc
        // 100.64.0.0/10
        return bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }

    /** The permission reaching [url] needs, given what the plugin declared. Null when [url] is not http(s). */
    fun permissionFor(declared: List<DeclaredPermission>, url: String): String? {
        val host = hostOf(url) ?: return null
        if (isLocalName(host)) return LOCAL
        if (declaredDomains(declared).any { matches(host, it) }) return DOMAINS
        return ANY
    }
}

/**
 * Network for plugins (docs/plugin-api.md 3 D1, D2). A contained plugin has no sockets: this is its only way out, and
 * every request is checked against the caller's grant, every redirect hop again, and logged with its host.
 */
internal object HostNetApis {
    /** The most a `net.http` body can be either way: it travels as JSON text inside the 256 KiB reply cap. */
    const val MAX_BODY = 128 * 1024

    /** The longest a request may take; it is also cut to what is left of the call the plugin is serving. */
    const val MAX_TIMEOUT_MS = 30_000L

    private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
    private val DROPPED_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "upgrade")

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun url(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (NetScope.hostOf(url) == null) invalid("url must be an http or https address")
        return url
    }

    private fun permissionOf(declared: List<DeclaredPermission>, args: JSONObject): String {
        val url = url(args)
        val permission = NetScope.permissionFor(declared, url) ?: invalid("url must be an http or https address")
        // Plain http is for the local network only: anything that crosses the internet is encrypted.
        if (permission != NetScope.LOCAL && !NetScope.isHttps(url)) invalid("only https addresses can be reached outside your local network")
        return permission
    }

    /**
     * The check every hop meets, the first included: the permission the URL needs is declared and granted (no prompt
     * here; the first hop already had its chance at the sheet in the broker), and a declared domain that resolves to the
     * local network needs `net.local` as well. The name is looked up only once the plugin may reach it.
     */
    fun reach(env: BrokerEnvironment, record: PluginRecord, url: String) {
        val declared = record.manifest.v2.permissions
        val permission = permissionOf(declared, JSONObject().put("url", url))
        val grants = env.grants(record.manifest.id)
        fun granted(id: String) = PluginGrants.stateOf(record, grants, id) == GrantState.GRANTED
        if (!granted(permission)) {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${record.manifest.label} may not reach ${NetScope.hostOf(url)}")
        }
        if (permission != NetScope.LOCAL) {
            val host = NetScope.hostOf(url)!!
            val local = env.addressesOf(host).any { NetScope.isLocalAddress(it) }
            if (local && !granted(NetScope.LOCAL)) {
                throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$host is on your local network, which ${record.manifest.label} may not reach")
            }
        }
    }

    private fun headers(args: JSONObject): Map<String, String> {
        val json = args.optJSONObject("headers") ?: return emptyMap()
        return buildMap {
            json.keys().forEach { name -> if (name.lowercase() !in DROPPED_HEADERS) put(name, json.optString(name)) }
        }
    }

    private fun body(args: JSONObject): ByteArray? {
        val bytes = when {
            args.has("bodyBase64") -> runCatching { Base64.getDecoder().decode(args.optString("bodyBase64")) }.getOrElse { invalid("bodyBase64 is not base64") }
            args.has("body") -> args.optString("body").toByteArray(Charsets.UTF_8)
            else -> return null
        }
        if (bytes.size > MAX_BODY) invalid("a request body is at most ${MAX_BODY / 1024} KiB; larger uploads are not offered")
        return bytes
    }

    private fun timeout(env: BrokerEnvironment, record: PluginRecord, args: JSONObject): Long {
        val asked = args.optLong("timeoutMs", MAX_TIMEOUT_MS).coerceIn(1_000L, MAX_TIMEOUT_MS)
        val left = env.remainingMs(record.manifest.id)?.minus(BrokerCore.PROVIDER_MARGIN_MS) ?: asked
        if (left < 1_000L) throw BrokerException(PluginErrorCode.TIMEOUT, "too little time is left of this call for a request")
        return minOf(asked, left)
    }

    /**
     * One request and its answer, the body of `net.http` and of `web.session fetch` (G3): [added] headers are droidtop's
     * own (a session's cookies) and replace any of the same name the plugin sent; [hopCheck] runs on every hop before
     * the network checks do.
     */
    fun request(
        env: BrokerEnvironment,
        record: PluginRecord,
        args: JSONObject,
        added: Map<String, String> = emptyMap(),
        hopCheck: (String) -> Unit = {},
    ): JSONObject {
        val method = args.optString("method", "GET").uppercase()
        if (method !in METHODS) invalid("method must be one of ${METHODS.joinToString()}")
        val replaced = added.keys.map { it.lowercase() }.toSet()
        val sent = headers(args).filterKeys { it.lowercase() !in replaced } + added
        val request = HttpCall(method, url(args), sent, body(args), timeout(env, record, args), MAX_BODY)
        val answer = env.http(request) { hop ->
            hopCheck(hop)
            reach(env, record, hop)
        }
        val asBase64 = when (args.optString("as")) {
            "base64" -> true
            "text" -> false
            else -> !answer.isText
        }
        return JSONObject()
            .put("status", answer.status)
            .put("url", answer.finalUrl)
            .put("headers", JSONObject(answer.headers as Map<*, *>))
            .put("truncated", answer.truncated)
            .apply {
                if (asBase64) put("bodyBase64", Base64.getEncoder().encodeToString(answer.body)) else put("body", answer.body.toString(Charsets.UTF_8))
            }
    }

    val ops: List<HostOp> = listOf(
        // D1: whether the device is online, and how.
        HostOp("net", "state", permission = "net.state") { env, _, _ -> env.netState() },
        // D2: one request and its answer, at most 128 KiB each way.
        HostOp(
            "net", "http",
            permissionFor = { declared, args, _ -> permissionOf(declared, args) },
            target = { NetScope.hostOf(it.optString("url")).orEmpty() },
            alwaysAudit = true,
        ) { env, record, args -> request(env, record, args) },
        // D2: a file of any size, as a job, into the plugin's own data (docs/plugin-api.md 3 H1).
        HostOp(
            "net", "download",
            permissionFor = { declared, args, _ -> permissionOf(declared, args) },
            target = { NetScope.hostOf(it.optString("url")).orEmpty() + " -> " + it.optString("name") },
            alwaysAudit = true,
        ) { env, record, args ->
            val url = url(args)
            val name = args.optString("name")
            val store = HostDataApis.storeOf(env, record)
            val file = store.resolve(name)
            reach(env, record, url)
            val jobId = env.startDownload(record, url, file, store) { hop -> reach(env, record, hop) }
                ?: throw BrokerException(PluginErrorCode.FAILED, "the download could not be started")
            JSONObject().put("jobId", jobId).put("name", name)
        },
    )
}

/** One `net.http` request, validated by the broker; [maxBytes] is how much of the answer is read. */
data class HttpCall(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMs: Long,
    val maxBytes: Int,
)

/** Its answer: [headers] lowercase, [body] at most the call's maxBytes, [truncated] when there was more. */
data class HttpAnswer(
    val status: Int,
    val finalUrl: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val truncated: Boolean,
) {
    val isText: Boolean
        get() = headers["content-type"]?.lowercase()?.let { type ->
            type.startsWith("text/") || "json" in type || "xml" in type || "javascript" in type || "x-www-form-urlencoded" in type
        } ?: false
}

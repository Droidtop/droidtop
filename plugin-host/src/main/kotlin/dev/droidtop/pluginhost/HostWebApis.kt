package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * A plugin's signed-in web session (docs/plugin-api.md 3 G3): sign-in in droidtop's own web view, requests that carry
 * the session, and opening a protected link in it to capture the file it serves. Every op needs `web.session` with the
 * sites the plugin declared; the session's cookies never reach the plugin.
 */
internal object HostWebApis {
    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun domains(record: PluginRecord): List<String> =
        WebSessions.declaredDomains(record.manifest.v2.permissions).ifEmpty {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${record.manifest.label} declared no sites for its web session")
        }

    private fun vault(env: BrokerEnvironment): PluginVault =
        env.vault() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop keeps no plugin secrets")

    private fun stored(env: BrokerEnvironment, record: PluginRecord): WebSessions.Stored? =
        WebSessions.Stored.parse(vault(env).getHost(record.manifest.id, WebSessions.VAULT_KEY))

    private fun save(env: BrokerEnvironment, record: PluginRecord, session: WebSessions.Stored?) {
        if (session == null || session.cookies.isEmpty()) {
            vault(env).deleteHost(record.manifest.id, WebSessions.VAULT_KEY)
        } else {
            vault(env).putHost(record.manifest.id, WebSessions.VAULT_KEY, session.toJson())
        }
    }

    /** An https address under one of the plugin's declared sites, or a refusal naming them. */
    private fun sessionUrl(args: JSONObject, domains: List<String>): String {
        val url = args.optString("url").trim()
        if (!NetScope.isHttps(url) || NetScope.hostOf(url) == null) invalid("url must be an https address")
        if (WebSessions.domainFor(url, domains) == null) invalid("url must be on ${domains.joinToString()}")
        return url
    }

    private fun show(env: BrokerEnvironment, record: PluginRecord, request: WebSessionRequest): WebSessionResult =
        env.webSession(request)
            ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop cannot show a web view for plugins")

    val ops: List<HostOp> = listOf(
        // The person signs in on the site's own page in droidtop's web view; the plugin learns only whether they did.
        HostOp(
            "web.session", "sign_in", permission = WebSessions.PERMISSION, userOnly = true, alwaysAudit = true,
            target = { NetScope.hostOf(it.optString("url")).orEmpty() },
        ) { env, record, args ->
            val domains = domains(record)
            val url = sessionUrl(args, domains)
            val doneCookie = args.optString("doneCookie").trim().takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
            val result = show(env, record, WebSessionRequest(record.manifest.label, WebSessionRequest.Mode.SIGN_IN, url, domains, stored(env, record), doneCookie))
            if (result.completed) save(env, record, result.session)
            JSONObject().put("signedIn", result.completed && result.session?.cookies?.isNotEmpty() == true)
        },
        HostOp("web.session", "status", permission = WebSessions.PERMISSION) { env, record, _ ->
            JSONObject().put("signedIn", stored(env, record)?.cookies?.isNotEmpty() == true)
        },
        HostOp("web.session", "clear", permission = WebSessions.PERMISSION, alwaysAudit = true) { env, record, _ ->
            JSONObject().put("cleared", vault(env).deleteHost(record.manifest.id, WebSessions.VAULT_KEY))
        },
        // One request with the session's cookies, to a declared site only, and never redirected off it with them.
        HostOp(
            "web.session", "fetch", permission = WebSessions.PERMISSION, alwaysAudit = true,
            target = { NetScope.hostOf(it.optString("url")).orEmpty() },
        ) { env, record, args ->
            val domains = domains(record)
            val url = sessionUrl(args, domains)
            val session = stored(env, record)
            val added = buildMap {
                session?.cookieFor(url, domains)?.let { put("Cookie", it) }
                session?.userAgent?.let { put("User-Agent", it) }
            }
            HostNetApis.request(env, record, args, added) { hop ->
                if (added.isNotEmpty() && WebSessions.domainFor(hop, domains) == null) {
                    throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "a signed-in request may not follow a redirect away from ${domains.joinToString()}")
                }
            }.put("signedIn", added.containsKey("Cookie"))
        },
        // A protected link opened in the session; the file the page starts is captured for the plugin's acquire reply.
        HostOp(
            "web.session", "open_in_session", permission = WebSessions.PERMISSION, userOnly = true, alwaysAudit = true,
            target = { NetScope.hostOf(it.optString("url")).orEmpty() },
        ) { env, record, args ->
            val domains = domains(record)
            val url = sessionUrl(args, domains)
            val result = show(env, record, WebSessionRequest(record.manifest.label, WebSessionRequest.Mode.OPEN, url, domains, stored(env, record), null))
            // The site may have renewed the session while the person was there.
            result.session?.takeIf { it.cookies.isNotEmpty() }?.let { save(env, record, it) }
            val download = result.download ?: return@HostOp JSONObject().put("captured", false)
            NetScope.hostOf(download.url) ?: invalid("the page started a download that is not an http or https address")
            val fileName = WebSessions.safeFileName(WebSessions.fileNameFor(download.url, download.contentDisposition))
            val headers = buildMap {
                download.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                download.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                download.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            }
            val token = WebSessions.keep(
                WebSessions.Captured(record.manifest.id, download.url, fileName, download.size?.takeIf { it > 0 }, download.mimeType, headers, env.nowMs()),
            )
            JSONObject()
                .put("captured", true)
                .put(
                    "download",
                    JSONObject().put("url", download.url).put("fileName", fileName).put("session", token).apply {
                        download.size?.takeIf { it > 0 }?.let { put("size", it) }
                        download.mimeType?.let { put("mimeType", it) }
                    },
                )
        },
    )
}

/** What the broker asks droidtop's web view to show for a plugin. */
data class WebSessionRequest(
    val pluginLabel: String,
    val mode: Mode,
    val url: String,
    val domains: List<String>,
    val session: WebSessions.Stored?,
    /** Sign-in ends by itself when the site sets this cookie (the site's "signed in" cookie). */
    val doneCookie: String?,
) {
    enum class Mode { SIGN_IN, OPEN }
}

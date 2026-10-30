package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * A plugin's signed-in web session (docs/plugin-api.md 3 G3): sign-in in droidtop's own web view, requests that carry
 * the session, and opening a protected link in it to capture the file it serves. Every op needs `web.session` with the
 * sites the plugin declared; the session's cookies never reach the plugin.
 */
internal object HostWebApis {
    /** The most downloads one `open_in_session` takes: an acquire reply's limit for several files. */
    const val MAX_CAPTURED = DownloadJobs.MAX_FILES

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun domains(record: PluginRecord): List<String> =
        WebSessions.declaredDomains(record.manifest.v2.permissions).ifEmpty {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${record.manifest.label} declared no sites for its web session")
        }

    private fun vault(env: BrokerEnvironment): PluginVault =
        env.vault() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop keeps no plugin secrets")

    private fun stored(env: BrokerEnvironment, record: PluginRecord): WebSessions.Stored? =
        WebSessions.Stored.parse(vault(env).getHost(record.manifest.id, WebSessions.VAULT_KEY))

    /**
     * The session a plugin's calls use: its own, plus droidtop's sign-in for each declared download site it keeps none
     * of its own for (Droidtop/tracker#648 slice 5). Only [stored] is ever saved back to the plugin's vault.
     */
    private fun inUse(env: BrokerEnvironment, record: PluginRecord): WebSessions.Stored? =
        DownloadSites.borrow(stored(env, record), env.sharedSites(record.manifest.id, WebSessions.declaredDomains(record.manifest.v2.permissions)))

    /** [session] as a view left it, split: the plugin's own sites are its to keep; a shared site goes back to droidtop. */
    private fun keepAfterView(env: BrokerEnvironment, record: PluginRecord, session: WebSessions.Stored?) {
        session ?: return
        val shared = env.sharedSites(record.manifest.id, WebSessions.declaredDomains(record.manifest.v2.permissions))
        val own = stored(env, record)
        val borrowed = shared.keys.filter { own?.cookies?.get(it).isNullOrBlank() }
        env.renewSharedSites(session.cookies.filterKeys { it in borrowed })
        val left = session.cookies.filterKeys { it !in borrowed }
        if (left.isNotEmpty()) save(env, record, session.copy(cookies = left, account = own?.account))
    }

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
            // A browser check passed in the view clears droidtop's requests to that site too (Droidtop/tracker#600).
            env.vault()?.let { SiteChecks.keep(it, record.manifest.id, result.session) }
            // A new sign-in may be another account: its name is the plugin's to tell again (set_account).
            if (result.completed) save(env, record, result.session?.copy(account = null))
            JSONObject().put("signedIn", result.completed && result.session?.cookies?.isNotEmpty() == true)
        },
        HostOp("web.session", "get_status", permission = WebSessions.PERMISSION) { env, record, _ ->
            val session = inUse(env, record)
            JSONObject().put("signedIn", session?.cookies?.isNotEmpty() == true).put("account", session?.account ?: JSONObject.NULL)
        },
        // The name of the account the session is signed in to, as the plugin read it from the site: droidtop shows it
        // on the plugin's account row (docs/SPEC.md 12a "A plugin's site account"). Nothing else uses it.
        HostOp("web.session", "set_account", permission = WebSessions.PERMISSION) { env, record, args ->
            val name = WebSessions.Stored.accountName(args.optString("name")) ?: invalid("name must be one line of 1 to 64 characters")
            val session = stored(env, record)?.takeIf { it.cookies.isNotEmpty() }
                ?: return@HostOp JSONObject().put("saved", false)
            save(env, record, session.copy(account = name))
            JSONObject().put("saved", true)
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
            val session = inUse(env, record)
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
            // Any https link: a source's download link is often a file host the forum links to. The session's cookies
            // still go only to the declared sites; the person sees the site the page is on in the view's header.
            val url = args.optString("url").trim().takeIf { NetScope.isHttps(it) && NetScope.hostOf(it) != null }
                ?: invalid("url must be an https address")
            // A page that offers several files (a game in parts, a game and its patch) is taken in one visit (#419): up to
            // `max` downloads, the acquire reply's limit for several files.
            val max = args.optInt("max", 1).coerceIn(1, MAX_CAPTURED)
            // A link a host account, a debrid account or the host's own API serves comes from there, without the page (#638);
            // the plugin gets the files as it would a captured download, and never the account or its key.
            val downloads = dev.droidtop.pluginhost.hosts.HostLinks.resolve(url) ?: run {
                val request = WebSessionRequest(record.manifest.label, WebSessionRequest.Mode.OPEN, url, domains, inUse(env, record), null, maxDownloads = max)
                // Out of sight first (Droidtop/tracker#783): a served host or a started download needs no page, and an
                // expired link is the plugin's to ask its site for again; only a page the person must act on is shown.
                val hidden = env.hiddenWebSession(request, PluginWebAccount.expiredText(record))
                if (hidden?.end == HiddenWebSession.End.EXPIRED) {
                    env.vault()?.let { SiteChecks.keep(it, record.manifest.id, hidden.result.session) }
                    return@HostOp JSONObject().put("captured", false).put("expired", true)
                }
                hidden?.takeIf { it.end == HiddenWebSession.End.PERSON }?.let { env.vault()?.let { v -> SiteChecks.keep(v, record.manifest.id, it.result.session) } }
                val result = hidden?.takeIf { it.end != HiddenWebSession.End.PERSON }?.result ?: show(env, record, request)
                // A browser check passed in the view clears droidtop's requests to that site too (Droidtop/tracker#600).
                env.vault()?.let { SiteChecks.keep(it, record.manifest.id, result.session) }
                // The site may have renewed the session while the person was there: the plugin's own sites are kept for
                // it, a shared download site's go back to droidtop.
                result.session?.takeIf { it.cookies.isNotEmpty() }?.let { keepAfterView(env, record, it) }
                // The person followed the page to a host droidtop serves (a masked link, #650): its resolvers take it, and
                // when none can, the view opens that host's page as it would have.
                result.handoff?.let { link ->
                    dev.droidtop.pluginhost.hosts.HostLinks.resolve(link)
                        ?: show(env, record, WebSessionRequest(record.manifest.label, WebSessionRequest.Mode.OPEN, link, domains, inUse(env, record), null, maxDownloads = max)).downloads
                } ?: result.downloads
            }
            if (downloads.isEmpty()) return@HostOp JSONObject().put("captured", false)
            val described = downloads.take(max).map { download ->
                NetScope.hostOf(download.url) ?: invalid("the page started a download that is not an http or https address")
                val fileName = WebSessions.safeFileName(WebSessions.fileNameFor(download.url, download.contentDisposition))
                val headers = buildMap {
                    download.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                    download.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                    download.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                    // A host's API gives its own, such as the person's account on the host (#638): in memory, by the token.
                    putAll(download.headers)
                }
                val token = WebSessions.keep(
                    WebSessions.Captured(record.manifest.id, download.url, fileName, download.size?.takeIf { it > 0 }, download.mimeType, headers, env.nowMs()),
                )
                JSONObject().put("url", download.url).put("fileName", fileName).put("session", token).apply {
                    download.size?.takeIf { it > 0 }?.let { put("size", it) }
                    download.mimeType?.let { put("mimeType", it) }
                }
            }
            JSONObject()
                .put("captured", true)
                .put("download", described.first())
                .put("downloads", org.json.JSONArray(described))
        },
    )
}

/** What droidtop's web view ([WebSessionActivity]) is asked to show: a plugin's session, or a store's own pages. */
data class WebSessionRequest(
    /** Whose session it is, for the view's header: the plugin's name, or the store's. */
    val label: String,
    val mode: Mode,
    val url: String,
    /** The sites whose cookies [session] puts in while the view is open and the view takes out when it closes. */
    val domains: List<String>,
    val session: WebSessions.Stored?,
    /** Sign-in ends by itself when the site sets this cookie (the site's "signed in" cookie). */
    val doneCookie: String?,
    /** [Mode.BROWSE]: told the address of each page the view finishes showing, on the main thread. Nothing else of a page is read. */
    val onPage: ((String) -> Unit)? = null,
    /**
     * [Mode.OPEN]: how many downloads the page may start before the view closes (Droidtop/tracker#419); above 1 the view
     * stays open, says how many it has, and Done ends it sooner.
     */
    val maxDownloads: Int = 1,
    /**
     * The site's own sign-in page, when the plugin declares one: a check or a download page offers it as a full page
     * (no in-page overlay), and with [doneCookie] a check stays open until the person is signed in (Droidtop/tracker#600).
     */
    val signInUrl: String? = null,
) {
    /**
     * A plugin's sign-in, a site's browser check passed in the plugin's session (docs/plugin-api.md 1.3
     * `BROWSER_CHECK_REQUIRED`), a plugin's protected link, or a store's own pages (docs/SPEC.md 7g, "A store's own pages").
     */
    enum class Mode {
        SIGN_IN,
        CHECK,
        OPEN,
        BROWSE,
        ;

        /** The person ends it with Done; backing out stores nothing. */
        val endsWithDone: Boolean get() = this == SIGN_IN || this == CHECK
    }
}

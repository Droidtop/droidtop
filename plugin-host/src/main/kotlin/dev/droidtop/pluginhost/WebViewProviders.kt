package dev.droidtop.pluginhost

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import dev.droidtop.shared.sitecheck.SiteCheck
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * droidtop's web pages shown in an app that provides `web.view` (docs/plugin-api.md 3 G3a): droidtop Browser, a GeckoView
 * browser, so a sign-in, a browser check or a download page runs on a current engine where the device's own WebView is
 * too old (owner, 2026-11-21; the Retroid Pocket 5's ROM allows only its own WebView 109, whose Cloudflare check loops).
 *
 * droidtop stays the vault and the judge; the app only shows the page:
 * - [open] starts the app's session screen with a one-use token. The app collects the request with `web.view.take_request`:
 *   the address, the sites, the cookies droidtop holds for them, and how the page ends.
 * - The app reports each page it finishes with `web.view.report_page`; droidtop answers whether the view is done (a browser
 *   check passed, the sign-in cookie set, or a link onto a file host droidtop serves, which closes there as a handoff).
 * - The app ends with `web.view.finish`: the cookies of the request's sites only, its user agent and what downloads the
 *   page started. droidtop seals the session in the plugin's vault and runs the downloads, exactly as after its own view.
 *
 * Store browse is embedded only with a separate provider context and no credential import or export. The person
 * turns this off with the app plugin's `web.view` point in its Permissions, which sends every page back to droidtop's view.
 */
object WebViewProviders {
    const val POINT = "web.view"

    /** The intent the app's session screen answers, sent to its package only. */
    const val ACTION = "dev.droidtop.pluginhost.action.WEB_VIEW"
    const val EXTRA_TOKEN = "dev.droidtop.pluginhost.web.token"

    /** How long a page shown in the app waits for the person; then the answer is "nothing done", as in droidtop's view. */
    private const val TIMEOUT_MS = 10L * 60 * 1000

    /** How long the app has to take a page it was started for before droidtop shows it in its own view instead. */
    private const val TAKE_MS = 30_000L
    private const val MAX_TEXT = 4096
    private const val TAG = "droidtop.WebSession"

    private class Pending(
        val pluginId: String,
        val request: WebSessionRequest,
        /** droidtop's own signed-in download sites put into the page, by domain (docs/SPEC.md 12a "Download sites"). */
        val sites: Map<String, String>,
        val onClosed: (WebSessionResult) -> Unit,
    ) {
        @Volatile var taken = false
        @Volatile var handoff: String? = null
        val bodies = ConcurrentHashMap<String, WebSessionDownload>()
        var bodyTransport: BrowserBodyTransport? = null
        @Volatile var bodyRequired = false
        var cleanup: (() -> Unit)? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** How an answer reaches its caller: on the main thread, as from droidtop's own view. Replaced in the unit tests. */
    internal var deliver: (() -> Unit) -> Unit = { answer -> main.post { answer() } }

    /**
     * The app plugin that shows droidtop's pages now: approved, running, `web.view` allowed, and its app still signed by
     * the certificate approved. Null sends the page to droidtop's own view. Off the main thread (it checks the APK).
     */
    fun provider(context: Context): PluginRecord? {
        val grants = PluginGrants.forContext(context)
        val resolution = PluginApiResolver.current(context)
        return PluginStore.installed(context)
            .asSequence()
            .filter { it.manifest.kind == PluginKind.APP_INTERFACE && it.runnable() && !resolution.isWaiting(it.manifest.id) }
            .filter { record -> record.manifest.v2.provides.any { it.point == POINT && ExtensionPoints.supports(it.point, it.version) } }
            .filter { PluginGrants.pointRefusal(it, grants.read(it.manifest.id), POINT) == null }
            .firstOrNull { record -> AppInterfaces.verifyRecord(context, record) == null }
    }

    /**
     * Shows [request] in the provider's app and returns at once, true when it was started; [onClosed] gets the answer on
     * the main thread. False (and [onClosed] never called) when no app provides `web.view` or it could not be started.
     * When the app has not taken the page within [TAKE_MS] (it crashed starting, as droidtop Browser's first build did on
     * the console, 2026-11-22), the page is withdrawn from it and [fallback] shows it in droidtop's own view instead, on
     * the main thread, so the person is never left waiting on an app that is not there.
     */
    fun open(
        context: Context,
        request: WebSessionRequest,
        sites: Map<String, String>,
        onClosed: (WebSessionResult) -> Unit,
        fallback: () -> Unit,
    ): Boolean {
        val record = runCatching { provider(context) }.onFailure { Log.w(TAG, "web.view provider could not be read", it) }.getOrNull() ?: return false
        if (Build.VERSION.SDK_INT < 30) return request.mode != WebSessionRequest.Mode.BROWSE && openLegacy(context, record, request, sites, onClosed, fallback)
        val packageName = record.manifest.app?.packageName ?: return false
        val token = register(record.manifest.id, request, sites, onClosed)
        if (request.mode == WebSessionRequest.Mode.OPEN) requireBodyBeforeTake(token)
        val surfaceFallback = {
            if (request.mode == WebSessionRequest.Mode.BROWSE || !openLegacy(context, record, request, sites, onClosed, fallback)) fallback()
        }
        if (!BrowserSurfaceActivity.open(context, record, token, request.label, request.mode == WebSessionRequest.Mode.BROWSE, surfaceFallback)) {
            pending.remove(token)
            return request.mode != WebSessionRequest.Mode.BROWSE && openLegacy(context, record, request, sites, onClosed, fallback)
        }
        Log.i(TAG, "${request.mode.name.lowercase()} for ${request.label} shown in ${record.manifest.label}")
        main.postDelayed({
            if (withdrawUntaken(token)) {
                BrowserSurfaceActivity.completed(token)
                Log.w(TAG, "${record.manifest.label} did not take the page in ${TAKE_MS / 1000} s; showing it in droidtop's own view")
                surfaceFallback()
            }
        }, TAKE_MS)
        main.postDelayed({ close(token, WebSessionResult(false, null, null)) }, TIMEOUT_MS)
        return true
    }

    /** Old providers and Android 26–29 retain Gecko in its own Activity, with a newly issued one-use token. */
    private fun openLegacy(context: Context, record: PluginRecord, request: WebSessionRequest, sites: Map<String, String>, onClosed: (WebSessionResult) -> Unit, fallback: () -> Unit): Boolean {
        if (request.mode == WebSessionRequest.Mode.BROWSE) return false
        val pkg = record.manifest.app?.packageName ?: return false
        val token = register(record.manifest.id, request, sites, onClosed)
        if (request.mode == WebSessionRequest.Mode.OPEN) requireBodyBeforeTake(token)
        fun start(): Boolean = runCatching {
            context.startActivity(Intent(ACTION).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_TOKEN, token))
        }.isSuccess
        if (request.mode == WebSessionRequest.Mode.OPEN) {
            BodyDownloadRelay.prepare(context, record, token,
                ready = { if (isUntaken(token) && !start() && withdrawUntaken(token)) fallback() },
                unavailable = {
                    if (withdrawUntaken(token)) {
                        dev.droidtop.library.settings.Notices.flash("Update droidtop Browser to transfer this download", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context.applicationContext)
                        fallback()
                    } else cancel(token)
                },
            )
        } else if (!start()) { pending.remove(token); return false }
        main.postDelayed({ if (withdrawUntaken(token)) fallback() }, TAKE_MS)
        main.postDelayed({ cancel(token) }, TIMEOUT_MS)
        return true
    }

    /** Takes back a page its app never took, true when it did: the caller then shows it another way, and the app is refused it. */
    internal fun withdrawUntaken(token: String): Boolean {
        val asked = pending[token] ?: return false
        val removed = synchronized(asked) {
            if (asked.taken) false else pending.remove(token, asked)
        }
        if (removed) release(asked)
        return removed
    }

    private fun isUntaken(token: String): Boolean {
        val asked = pending[token] ?: return false
        return synchronized(asked) { pending[token] === asked && !asked.taken }
    }

    /** A page given to [pluginId]'s app, by its new one-use token. */
    internal fun register(pluginId: String, request: WebSessionRequest, sites: Map<String, String>, onClosed: (WebSessionResult) -> Unit): String {
        val token = UUID.randomUUID().toString()
        pending[token] = Pending(pluginId, request, sites, onClosed)
        return token
    }

    /** `web.view.take_request`: the request behind [token], once, to the plugin it was given to. */
    internal fun take(pluginId: String, token: String): JSONObject {
        val asked = pending[token]?.takeIf { it.pluginId == pluginId } ?: refuse("no such web view")
        synchronized(asked) {
            if (pending[token] !== asked || asked.taken) refuse("this web view was already taken or withdrawn")
            if (asked.bodyRequired && asked.bodyTransport == null) refuse("the browser response capability is not ready")
            asked.taken = true
        }
        val request = asked.request
        val cookies = JSONObject()
        val browse = request.mode == WebSessionRequest.Mode.BROWSE
        if (!browse) {
            request.session?.cookies?.forEach { (domain, header) -> if (domain in request.domains) cookies.put(domain, header) }
            asked.sites.forEach { (domain, header) -> cookies.put(domain, header) }
        }
        return JSONObject()
            .put("mode", request.mode.name.lowercase())
            .put("label", request.label)
            .put("url", request.url)
            .put("domains", JSONArray(if (browse) emptyList<String>() else request.domains))
            .put("cookies", cookies)
            .put("maxDownloads", if (request.mode == WebSessionRequest.Mode.OPEN) request.maxDownloads else 0)
            .put("signInUrl", request.signInUrl?.takeIf { !browse && WebSurfaceEvents.safeUrl(it) } ?: JSONObject.NULL)
            // The site's "signed in" cookie: the app says in each report_page whether it is set on the request's sites.
            .put("doneCookie", request.doneCookie?.takeUnless { browse } ?: JSONObject.NULL)
    }

    /**
     * `web.view.report_page`: the app finished showing [url] (with the page's [title] and the cookies its host has now); the
     * answer says whether the view is done, by the same rules as droidtop's own view.
     */
    internal fun page(pluginId: String, token: String, url: String, title: String?, hostCookies: String?, signedIn: Boolean?): JSONObject {
        val asked = pending[token]?.takeIf { it.pluginId == pluginId && it.taken } ?: refuse("no such web view")
        val request = asked.request
        if (request.mode == WebSessionRequest.Mode.BROWSE) {
            if (WebSurfaceEvents.safeUrl(url)) deliver { request.onPage?.invoke(url) }
            return JSONObject().put("finish", false)
        }
        val host = NetScope.hostOf(url)
        val finish = when {
            host == null -> false
            // A link onto a file host droidtop serves closes there, before anything was taken (mytop-project/tracker#650).
            request.mode == WebSessionRequest.Mode.OPEN && NetScope.isHttps(url) && dev.droidtop.pluginhost.hosts.HostLinks.served(url) -> {
                asked.handoff = url
                Log.i(TAG, "$host: handed to droidtop's host resolvers")
                true
            }
            // A browser check passes on the site's own page with a pass cookie for it, and, where the plugin signs in
            // there, once it is signed in (mytop-project/tracker#600).
            request.mode == WebSessionRequest.Mode.CHECK &&
                (request.domains.isEmpty() || WebSessions.covers(host, request.domains)) &&
                SiteCheck.pagePassed(title, hostCookies) -> signedIn != false
            request.mode == WebSessionRequest.Mode.SIGN_IN -> signedIn == true
            else -> false
        }
        return JSONObject().put("finish", finish)
    }

    /** `web.view.finish`: what the page left; droidtop keeps the request's sites' cookies and the downloads it started. */
    internal fun finish(context: Context?, pluginId: String, token: String, args: JSONObject): Boolean {
        val asked = pending[token]?.takeIf { it.pluginId == pluginId && it.taken } ?: refuse("no such web view")
        synchronized(asked) {
            if (!pending.remove(token, asked)) refuse("this web view already finished")
        }
        var reported = false
        try {
            val request = asked.request
            if (request.mode == WebSessionRequest.Mode.BROWSE) {
                reported = true
                complete(token, asked, WebSessionResult(false, null, null))
                return true
            }
            val left = args.optJSONObject("cookies") ?: JSONObject()
            fun cookieFor(domain: String) = header(left.optString(domain), MAX_TEXT * 4)
            val userAgent = header(args.optString("userAgent"), MAX_TEXT)
            val session = WebSessions.Stored(
                userAgent = userAgent,
                cookies = request.domains.mapNotNull { domain -> cookieFor(domain)?.let { domain to it } }.toMap(),
            )
            val renewed = asked.sites.keys.mapNotNull { domain -> cookieFor(domain)?.let { domain to it } }
            val downloads = if (request.mode == WebSessionRequest.Mode.OPEN) finishDownloads(asked, args.optJSONArray("downloads")).map { download ->
                val host = NetScope.hostOf(download.url)
                val scoped = host != null && WebSessions.covers(host, request.domains + asked.sites.keys)
                if (download.cookie != null && !scoped) throw BrokerException(PluginErrorCode.UNSUPPORTED, "This download needs cookies outside this page's approved sites; open that site in its own signed-in session")
                download
            } else emptyList()
            if (renewed.isNotEmpty() && context != null) DownloadSites.renewLater(context.applicationContext, renewed)
            val completed = args.optBoolean("completed", false)
            reported = true
            complete(token, asked, WebSessionResult(completed, session, downloads.firstOrNull(), downloads, asked.handoff))
            return true
        } catch (error: Exception) {
            if (!reported) {
                if (context != null) deliver {
                    val reason = if (error is BrokerException) error.message.orEmpty().take(512) else "The browser result could not be kept; reopen the page and try again"
                    dev.droidtop.library.settings.Notices.flash(reason, tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context.applicationContext)
                }
                complete(token, asked, WebSessionResult(false, null, null))
            }
            throw error
        }
    }

    /** The downloads an app reports: web addresses only, each once, at most [max]. */
    internal fun downloadsOf(list: JSONArray?, max: Int): List<WebSessionDownload> {
        list ?: return emptyList()
        return (0 until list.length().coerceAtMost(256)).mapNotNull { list.optJSONObject(it) }.mapNotNull { item ->
            val url = item.optString("url").trim()
            if (!WebSurfaceEvents.safeUrl(url)) return@mapNotNull null
            if (url.length > MAX_TEXT) return@mapNotNull null
            fun text(key: String) = header(item.optString(key), MAX_TEXT)
            WebSessionDownload(
                url = url,
                userAgent = text("userAgent"),
                contentDisposition = text("contentDisposition"),
                mimeType = text("mimeType"),
                size = item.optLong("size", -1L).takeIf { it > 0 },
                referer = text("referer")?.also { if (!WebSurfaceEvents.safeUrl(it)) refuse("the download has an invalid referring page") },
                cookie = header(item.optString("cookie"), MAX_TEXT * 4),
            )
        }.distinctBy { it.url }.take(max.coerceIn(0, 256))
    }

    private fun close(token: String, result: WebSessionResult) {
        val asked = pending[token] ?: return
        if (!synchronized(asked) { pending.remove(token, asked) }) return
        complete(token, asked, result)
    }

    private fun complete(token: String, asked: Pending, result: WebSessionResult) {
        release(asked)
        val keptBodies = result.downloads.mapNotNull { it.headers[BrowserBodyOffers.TOKEN] }.toSet()
        asked.bodies.values.filter { it.headers[BrowserBodyOffers.TOKEN] !in keptBodies }.forEach { BrowserBodyOffers.revoke(it.headers) }
        deliver {
            if (Build.VERSION.SDK_INT >= 30) BrowserSurfaceActivity.completed(token)
            asked.onClosed(result)
        }
    }

    /** A closed/dead embedded surface cannot replay the page in another engine after take_request. */
    internal fun cancel(token: String) = close(token, WebSessionResult(false, null, null))

    /** A browse surface reports addresses only; its private cookies, forms and downloads never enter the broker. */
    internal fun surfacePage(token: String, url: String) {
        val asked = pending[token]?.takeIf { it.taken && it.request.mode == WebSessionRequest.Mode.BROWSE } ?: return
        if (WebSurfaceEvents.safeUrl(url)) asked.request.onPage?.invoke(url)
    }

    internal fun bodyOffer(context: Context, token: String, providerId: String, provider: BrowserBodyTransport, metadata: BrowserBodyMetadata): Boolean {
        val asked = pending[token]?.takeIf { it.taken && it.request.mode == WebSessionRequest.Mode.OPEN } ?: return false
        return synchronized(asked) {
            if (pending[token] !== asked || asked.bodies.size >= asked.request.maxDownloads.coerceIn(0, DownloadJobs.MAX_FILES) || asked.bodies.containsKey(providerId)) return false
            if (asked.bodyTransport?.binder != provider.binder) return false
            val download = BrowserBodyOffers.offer(context, asked.pluginId, token, providerId, provider, metadata) ?: return false
            asked.bodies[providerId] = download
            true
        }
    }

    /** Established before take_request; a body callback cannot add authority to an already-taken token. */
    internal fun bodyReady(token: String, transport: BrowserBodyTransport): Boolean {
        val asked = pending[token] ?: return false
        return synchronized(asked) {
            if (pending[token] !== asked || asked.taken) false else { asked.bodyTransport = transport; true }
        }
    }

    internal fun requireBodyBeforeTake(token: String) {
        val asked = pending[token] ?: return
        synchronized(asked) { check(!asked.taken); asked.bodyRequired = true }
    }

    internal fun bodyIsRequired(token: String): Boolean = pending[token]?.bodyRequired == true

    internal fun keepBodyProvider(token: String, cleanup: () -> Unit): Boolean {
        val asked = pending[token] ?: return false
        return synchronized(asked) {
            if (pending[token] !== asked || asked.cleanup != null) false else { asked.cleanup = cleanup; true }
        }
    }

    private fun release(asked: Pending) {
        val cleanup = synchronized(asked) { asked.cleanup.also { asked.cleanup = null } }
        cleanup?.invoke()
    }

    private fun finishDownloads(asked: Pending, list: JSONArray?): List<WebSessionDownload> {
        list ?: return emptyList()
        return (0 until list.length().coerceAtMost(256)).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            if (item.has("streamOfferId")) {
                if (item.keys().asSequence().toSet() != setOf("streamOfferId")) refuse("a browser body descriptor may only name its offer")
                asked.bodies[item.optString("streamOfferId")] ?: refuse("the browser body was not offered to this page")
            } else downloadsOf(JSONArray().put(item), 1).singleOrNull()
        }.distinctBy { it.headers[BrowserBodyOffers.TOKEN] ?: it.url }.take(asked.request.maxDownloads.coerceIn(0, DownloadJobs.MAX_FILES))
    }

    private fun refuse(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun header(value: String, max: Int): String? {
        if (value.isBlank()) return null
        if (value.length > max || value.any { it.isISOControl() }) refuse("the browser returned an invalid header")
        return value
    }
}

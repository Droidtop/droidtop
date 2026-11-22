package dev.droidtop.pluginhost

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
 * A store's own pages ([WebSessionRequest.Mode.BROWSE]) stay in droidtop's view: a checkout is typed there. The person
 * turns this off with the app plugin's `web.view` point in its Permissions, which sends every page back to droidtop's view.
 */
object WebViewProviders {
    const val POINT = "web.view"

    /** The intent the app's session screen answers, sent to its package only. */
    const val ACTION = "dev.droidtop.pluginhost.action.WEB_VIEW"
    const val EXTRA_TOKEN = "dev.droidtop.pluginhost.web.token"

    /** How long a page shown in the app waits for the person; then the answer is "nothing done", as in droidtop's view. */
    private const val TIMEOUT_MS = 10L * 60 * 1000
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
        return PluginStore.installed(context)
            .asSequence()
            .filter { it.manifest.kind == PluginKind.APP_INTERFACE && it.runnable() && !PluginApiResolver.isWaiting(it.manifest.id) }
            .filter { record -> record.manifest.v2.provides.any { it.point == POINT && ExtensionPoints.supports(it.point, it.version) } }
            .filter { PluginGrants.pointRefusal(it, grants.read(it.manifest.id), POINT) == null }
            .firstOrNull { record -> AppInterfaces.verifyRecord(context, record) == null }
    }

    /**
     * Shows [request] in the provider's app and returns at once, true when it was started; [onClosed] gets the answer on
     * the main thread. False (and [onClosed] never called) when no app provides `web.view` or it could not be started.
     */
    fun open(context: Context, request: WebSessionRequest, sites: Map<String, String>, onClosed: (WebSessionResult) -> Unit): Boolean {
        if (request.mode == WebSessionRequest.Mode.BROWSE) return false
        val record = runCatching { provider(context) }.onFailure { Log.w(TAG, "web.view provider could not be read", it) }.getOrNull() ?: return false
        val packageName = record.manifest.app?.packageName ?: return false
        val token = register(record.manifest.id, request, sites, onClosed)
        val intent = Intent(ACTION).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_TOKEN, token)
        }
        if (runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "$packageName: its web view could not be started", it) }.isFailure) {
            pending.remove(token)
            return false
        }
        Log.i(TAG, "${request.mode.name.lowercase()} for ${request.label} shown in ${record.manifest.label}")
        main.postDelayed({ close(token, WebSessionResult(false, null, null)) }, TIMEOUT_MS)
        return true
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
        if (asked.taken) refuse("this web view was already taken")
        asked.taken = true
        val request = asked.request
        val cookies = JSONObject()
        request.session?.cookies?.forEach { (domain, header) -> if (domain in request.domains) cookies.put(domain, header) }
        asked.sites.forEach { (domain, header) -> cookies.put(domain, header) }
        return JSONObject()
            .put("mode", request.mode.name.lowercase())
            .put("label", request.label)
            .put("url", request.url)
            .put("domains", JSONArray(request.domains))
            .put("cookies", cookies)
            .put("maxDownloads", if (request.mode == WebSessionRequest.Mode.OPEN) request.maxDownloads else 0)
            .put("signInUrl", request.signInUrl?.takeIf { NetScope.isHttps(it) } ?: JSONObject.NULL)
    }

    /**
     * `web.view.report_page`: the app finished showing [url] (with the page's [title] and the cookies its host has now); the
     * answer says whether the view is done, by the same rules as droidtop's own view.
     */
    internal fun page(pluginId: String, token: String, url: String, title: String?, hostCookies: String?, signedIn: Boolean?): JSONObject {
        val asked = pending[token]?.takeIf { it.pluginId == pluginId && it.taken } ?: refuse("no such web view")
        val request = asked.request
        val host = NetScope.hostOf(url)
        val finish = when {
            host == null -> false
            // A link onto a file host droidtop serves closes there, before anything was taken (Droidtop/tracker#650).
            request.mode == WebSessionRequest.Mode.OPEN && NetScope.isHttps(url) && dev.droidtop.pluginhost.hosts.HostLinks.served(url) -> {
                asked.handoff = url
                Log.i(TAG, "$host: handed to droidtop's host resolvers")
                true
            }
            // A browser check passes on the site's own page with a pass cookie for it, and, where the plugin signs in
            // there, once it is signed in (Droidtop/tracker#600).
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
        val asked = pending[token]?.takeIf { it.pluginId == pluginId } ?: refuse("no such web view")
        val request = asked.request
        val left = args.optJSONObject("cookies") ?: JSONObject()
        fun cookieFor(domain: String) = left.optString(domain).takeIf { it.isNotBlank() && it.length <= MAX_TEXT * 4 }
        val userAgent = args.optString("userAgent").takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
        val session = WebSessions.Stored(
            userAgent = userAgent,
            cookies = request.domains.mapNotNull { domain -> cookieFor(domain)?.let { domain to it } }.toMap(),
        )
        val renewed = asked.sites.keys.mapNotNull { domain -> cookieFor(domain)?.let { domain to it } }
        if (renewed.isNotEmpty() && context != null) DownloadSites.renewLater(context.applicationContext, renewed)
        val downloads = if (request.mode == WebSessionRequest.Mode.OPEN) downloadsOf(args.optJSONArray("downloads"), request.maxDownloads) else emptyList()
        val completed = args.optBoolean("completed", false)
        close(token, WebSessionResult(completed, session, downloads.firstOrNull(), downloads, asked.handoff))
        return true
    }

    /** The downloads an app reports: web addresses only, each once, at most [max]. */
    internal fun downloadsOf(list: JSONArray?, max: Int): List<WebSessionDownload> {
        list ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { item ->
            val url = item.optString("url").trim()
            if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return@mapNotNull null
            if (url.length > MAX_TEXT) return@mapNotNull null
            fun text(key: String) = item.optString(key).takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
            WebSessionDownload(
                url = url,
                userAgent = text("userAgent"),
                contentDisposition = text("contentDisposition"),
                mimeType = text("mimeType"),
                size = item.optLong("size", -1L).takeIf { it > 0 },
                referer = text("referer"),
                cookie = item.optString("cookie").takeIf { it.isNotBlank() && it.length <= MAX_TEXT * 4 },
            )
        }.distinctBy { it.url }.take(max.coerceAtLeast(1))
    }

    private fun close(token: String, result: WebSessionResult) {
        val asked = pending.remove(token) ?: return
        deliver { asked.onClosed(result) }
    }

    private fun refuse(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)
}

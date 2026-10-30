package dev.droidtop.pluginhost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.droidtop.shared.sitecheck.SiteCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * A plugin's download link opened out of sight first (docs/SPEC.md 12a "A download link opens out of sight first",
 * Droidtop/tracker#783). The owner: "open the link in the hidden webview, check for the text currently onscreen, and if
 * we see it, refresh the link and launch that. Otherwise, display the webview to the user (if they need to look at
 * it)". The view gets the same session as the visible one ([WebSessionCookies]) in droidtop's window
 * ([HiddenWebViews]), and ends on one of [End]; only [End.PERSON] opens droidtop's visible view. Blocking: never on the
 * main thread.
 */
object HiddenWebSession {
    private const val TAG = "droidtop.WebSession"

    /** How long a check page may take to pass by itself, as for [WebFetch]. */
    private const val SETTLE_MS = WebFetch.SETTLE_MS

    /** How long a finished page with nothing to act on is given to move on by itself (a page that redirects in script). */
    private const val STILL_MS = 3_000L

    /** The longest a hidden open takes before it is the person's. */
    private const val TIMEOUT_MS = 40_000L

    enum class End {
        /** The page led to a host droidtop serves ([WebSessionResult.handoff]): its resolvers take it, no page shown. */
        HANDOFF,

        /** The page started a download, taken as the visible view takes one. */
        DOWNLOAD,

        /** The page says the link has expired, in words the plugin declared ([PluginWebAccount.expiredText]). */
        EXPIRED,

        /** The person has something to do: a check that did not pass by itself, a sign-in, a page with nothing served. */
        PERSON,
    }

    data class Outcome(val end: End, val result: WebSessionResult)

    /**
     * Opens [request] (an Open view's) out of sight and returns how it ended. Null when there is no droidtop screen in
     * front to run it in: a page in no window passes no check (Droidtop/tracker#783), so the visible view opens instead.
     */
    fun open(context: Context, request: WebSessionRequest, expiredText: List<String>): Outcome? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "HiddenWebSession blocks; call it off the main thread" }
        val app = context.applicationContext
        val sites = runCatching { DownloadSites.cookies(app, except = request.domains) }.getOrNull()?.takeIf { it.isNotEmpty() }
        val done = CompletableDeferred<Outcome?>()
        var abandon: (() -> Unit)? = null
        Handler(Looper.getMainLooper()).post {
            runCatching { abandon = start(app, request, sites, expiredText, done) }
                .onFailure { Log.w(TAG, "no hidden web view: ${it.message}"); done.complete(null) }
        }
        val outcome = runBlocking { withTimeoutOrNull(TIMEOUT_MS) { done.await() } }
        // Ended on its own, or found no window (null).
        if (outcome != null || done.isCompleted) return outcome
        // Too long: the person's, and the view ends (its session taken out of the store) on the main thread.
        Handler(Looper.getMainLooper()).post { abandon?.invoke() }
        return Outcome(End.PERSON, WebSessionResult(false, null, null))
    }

    /** On the main thread: the view, in droidtop's window, loading [request]'s link. Returns what ends it early. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun start(
        app: Context,
        request: WebSessionRequest,
        sites: Map<String, String>?,
        expiredText: List<String>,
        done: CompletableDeferred<Outcome?>,
    ): () -> Unit {
        val cookies = CookieManager.getInstance()
        val view = HiddenWebViews.open() ?: run {
            done.complete(null)
            return {}
        }
        WebSessionCookies.putIn(cookies, request, sites)
        WebSessionActivity.webViewSetup?.invoke(view)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cookies.setAcceptThirdPartyCookies(view, true)
        val host = NetScope.hostOf(request.url).orEmpty()
        view.webChromeClient = HiddenWebViews.chromeClient(TAG, host)
        val handler = Handler(Looper.getMainLooper())
        val startedAt = System.currentTimeMillis()
        // Each page the view finishes; a wait for an earlier one is dropped when the view has moved on.
        var shown = 0

        fun finish(end: End, download: WebSessionDownload? = null, handoff: String? = null) {
            if (done.isCompleted) return
            val (session, renewed) = WebSessionCookies.takeOut(cookies, request, sites, view.settings.userAgentString)
            DownloadSites.renewLater(app, renewed)
            Log.i(TAG, "$host: out of sight, ${end.name.lowercase()}")
            done.complete(Outcome(end, WebSessionResult(end != End.PERSON, session, download, listOfNotNull(download), handoff)))
            handler.post {
                view.stopLoading()
                HiddenWebViews.detach(view)
                view.destroy()
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, asked: WebResourceRequest): Boolean {
                val scheme = asked.url.scheme?.lowercase()
                val target = asked.url.toString()
                // A host droidtop serves, as in the visible view (Droidtop/tracker#650): with the person's TorBox key a
                // pixeldrain link comes "via your TorBox", and no page is shown.
                if (asked.isForMainFrame && scheme == "https" && dev.droidtop.pluginhost.hosts.HostLinks.served(target)) {
                    view.post { finish(End.HANDOFF, handoff = target) }
                    return true
                }
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (done.isCompleted) return
                val page = ++shown
                val checking = SiteCheck.isCheckTitle(view.title)
                url?.let { NetScope.hostOf(it) }?.let { WebViewProbe.report("hidden open", it, view, startedAt, if (checking) "check page" else "page") }
                if (checking) {
                    // A check that passes by itself loads the site's page next; one that does not is the person's.
                    if (System.currentTimeMillis() - startedAt > SETTLE_MS) {
                        finish(End.PERSON)
                    } else {
                        handler.postDelayed({ if (!done.isCompleted && page == shown) onPageFinished(view, view.url) }, 2_000L)
                    }
                    return
                }
                view.evaluateJavascript("document.body ? document.body.innerText : ''") { quoted ->
                    if (done.isCompleted || page != shown) return@evaluateJavascript
                    val text = runCatching { JSONArray("[$quoted]").getString(0) }.getOrDefault("")
                    if (expired(text, expiredText)) {
                        finish(End.EXPIRED)
                        return@evaluateJavascript
                    }
                    // Nothing served, started or expired: a page that moves on by itself is given a moment, then it is
                    // the person's to look at.
                    handler.postDelayed({ if (!done.isCompleted && page == shown) finish(End.PERSON) }, STILL_MS)
                }
            }
        }
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            // A file only the page can build (a blob: address) is the visible view's, which says so.
            if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
                finish(End.PERSON)
                return@setDownloadListener
            }
            val download = WebSessionDownload(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                size = contentLength.takeIf { it > 0 },
                referer = view.url,
                cookie = cookies.getCookie(url),
            )
            finish(End.DOWNLOAD, download = download)
        }
        view.loadUrl(request.url)
        return { finish(End.PERSON) }
    }

    /** Whether the page's [text] carries one of the plugin's [expiredText] sentences, ignoring case and spacing. Pure. */
    fun expired(text: String, expiredText: List<String>): Boolean {
        val page = text.lowercase().replace(Regex("\\s+"), " ")
        return expiredText.any { sentence ->
            val wanted = sentence.lowercase().replace(Regex("\\s+"), " ").trim()
            wanted.isNotEmpty() && page.contains(wanted)
        }
    }
}

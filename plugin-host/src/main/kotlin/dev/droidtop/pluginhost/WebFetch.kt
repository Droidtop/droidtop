package dev.droidtop.pluginhost

import dev.droidtop.shared.sitecheck.SiteCheck
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * A page fetched by droidtop's web view instead of its HTTP client (Droidtop/tracker#600): Cloudflare tells
 * droidtop's client from a browser by more than cookies and user agent, so on a host that has shown a check a plugin's
 * GET is loaded by a web view out of sight ([HiddenWebViews]) with the cookies it would have sent, and the plugin gets
 * the page's final HTML as an ordinary answer. A check the view passes by itself (most do, in a few seconds) is waited for; one that
 * still shows after [SETTLE_MS] is the person's to pass, as before. Blocking: never on the main thread.
 */
object WebFetch {
    /**
     * How long a check page may take to pass by itself before it is the person's. It was 12 s; on the console
     * (Droidtop/tracker#783) the hidden view gave up at 12.7 s and the visible view then passed the same check unaided,
     * so the hidden one gets as long as a person would wait. [WebViewProbe] logs how long each view takes to pass. The
     * plugin's own request timeout still bounds it.
     */
    const val SETTLE_MS = 25_000L
    private const val TAG = "droidtop.WebFetch"

    /** The document's type, its markup, and its text, as one JSON string. */
    private const val READ_DOCUMENT = "JSON.stringify({type: document.contentType, html: document.documentElement.outerHTML, " +
        "text: document.body ? document.body.innerText : document.documentElement.textContent})"

    /** What the view ended on: the page ([answer]), or a check it could not pass ([challenged]), and the cookies it now holds for the host. */
    data class Result(val answer: HttpAnswer?, val challenged: Boolean, val cookies: String?)

    /**
     * Loads [url] with [cookies] (a Cookie header for its host) and returns the final page, at most [maxBytes] of it.
     * Null when no web view could be made or [timeoutMs] passed.
     */
    fun fetch(context: Context, url: String, cookies: String?, timeoutMs: Long, maxBytes: Int): Result? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WebFetch blocks; call it off the main thread" }
        val host = NetScope.hostOf(url) ?: return null
        val done = CompletableDeferred<Result?>()
        Handler(Looper.getMainLooper()).post {
            runCatching { start(context.applicationContext, url, host, cookies, maxBytes, done) }
                .onFailure { Log.w(TAG, "$host: no web view to fetch with: ${it.message}"); done.complete(null) }
        }
        return runBlocking { withTimeoutOrNull(timeoutMs) { done.await() } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(context: Context, url: String, host: String, cookies: String?, maxBytes: Int, done: CompletableDeferred<Result?>) {
        val jar = CookieManager.getInstance()
        jar.setAcceptCookie(true)
        cookies?.split(';')?.map { it.trim() }?.filter { it.contains('=') }?.forEach { pair ->
            jar.setCookie("https://$host", "$pair; Path=/; Secure")
        }
        // Made with droidtop's screen in front and put behind it, out of sight: a page in no window never finishes a check
        // (Droidtop/tracker#783). With no droidtop screen in front it runs in no window, as before.
        val view = HiddenWebViews.open() ?: WebView(context)
        val shown = view.isAttachedToWindow
        WebSessionActivity.webViewSetup?.invoke(view)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        jar.setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = HiddenWebViews.chromeClient(TAG, host)
        Log.i(TAG, "$host: fetching in ${if (shown) "droidtop's window, out of sight" else "no window: no droidtop screen is in front"}")
        val handler = Handler(Looper.getMainLooper())
        val startedAt = System.currentTimeMillis()
        var status = 200

        fun finish(result: Result?) {
            if (done.isCompleted) return
            done.complete(result)
            handler.post {
                view.stopLoading()
                HiddenWebViews.detach(view)
                view.destroy()
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme?.lowercase() != "https"

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) status = response.statusCode
            }

            override fun onPageFinished(view: WebView, finished: String?) {
                if (done.isCompleted) return
                val at = finished ?: url
                val checking = SiteCheck.isCheckTitle(view.title)
                WebViewProbe.report("hidden fetch", host, view, startedAt, if (checking) "check page" else "page")
                if (checking) {
                    // A check that passes by itself loads the site's page next; one that does not is the person's.
                    if (System.currentTimeMillis() - startedAt > SETTLE_MS) {
                        Log.i(TAG, "$host: the check did not pass by itself in ${SETTLE_MS / 1000} s")
                        finish(Result(null, challenged = true, cookies = jar.getCookie("https://$host")))
                    } else {
                        handler.postDelayed({
                            if (!done.isCompleted && SiteCheck.isCheckTitle(view.title)) onPageFinished(view, view.url)
                        }, 2_000L)
                    }
                    return
                }
                // The document as the site sent it: an HTML page's markup, or the text of anything else (a JSON reply,
                // which the view shows wrapped in a page of its own).
                view.evaluateJavascript(READ_DOCUMENT) { quoted ->
                    val read = runCatching { JSONObject(JSONArray("[$quoted]").getString(0)) }.getOrNull()
                    val type = read?.optString("type").orEmpty().ifBlank { "text/html" }
                    val html = type.equals("text/html", ignoreCase = true)
                    val text = read?.optString(if (html) "html" else "text").orEmpty()
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    val answer = HttpAnswer(
                        status = status,
                        finalUrl = at,
                        headers = mapOf("content-type" to "$type; charset=utf-8"),
                        body = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes,
                        truncated = bytes.size > maxBytes,
                    )
                    finish(Result(answer, challenged = false, cookies = jar.getCookie("https://$host")))
                }
            }
        }
        view.loadUrl(url)
    }
}

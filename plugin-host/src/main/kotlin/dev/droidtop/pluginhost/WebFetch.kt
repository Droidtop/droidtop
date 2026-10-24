package dev.droidtop.pluginhost

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

/**
 * A page fetched by droidtop's web view instead of its HTTP client (Droidtop/tracker#600): Cloudflare tells
 * droidtop's client from a browser by more than cookies and user agent, so on a host that has shown a check a plugin's
 * GET is loaded by an off-screen web view with the cookies it would have sent, and the plugin gets the page's final
 * HTML as an ordinary answer. A check the view passes by itself (most do, in a few seconds) is waited for; one that
 * still shows after [SETTLE_MS] is the person's to pass, as before. Blocking: never on the main thread.
 */
object WebFetch {
    /** How long a check page may take to pass by itself before it is the person's. */
    private const val SETTLE_MS = 12_000L
    private const val TAG = "droidtop.WebFetch"

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
        val view = WebView(context)
        WebSessionActivity.webViewSetup?.invoke(view)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        jar.setAcceptThirdPartyCookies(view, true)
        val handler = Handler(Looper.getMainLooper())
        val startedAt = System.currentTimeMillis()
        var status = 200

        fun finish(result: Result?) {
            if (done.isCompleted) return
            done.complete(result)
            handler.post {
                view.stopLoading()
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
                if (SiteChecks.isCheckTitle(view.title)) {
                    // A check that passes by itself loads the site's page next; one that does not is the person's.
                    if (System.currentTimeMillis() - startedAt > SETTLE_MS) {
                        Log.i(TAG, "$host: the check did not pass by itself")
                        finish(Result(null, challenged = true, cookies = jar.getCookie("https://$host")))
                    } else {
                        handler.postDelayed({
                            if (!done.isCompleted && SiteChecks.isCheckTitle(view.title)) onPageFinished(view, view.url)
                        }, 2_000L)
                    }
                    return
                }
                view.evaluateJavascript("document.documentElement.outerHTML") { quoted ->
                    val html = runCatching { JSONArray("[$quoted]").getString(0) }.getOrDefault("")
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val answer = HttpAnswer(
                        status = status,
                        finalUrl = at,
                        headers = mapOf("content-type" to "text/html; charset=utf-8"),
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

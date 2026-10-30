package dev.droidtop.pluginhost

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * What a web view and its page look like to a site's check, logged the same way for droidtop's visible view and its
 * hidden ones (Droidtop/tracker#783): on the console F95's check passed at once in the visible view and not in the
 * hidden one, in droidtop's window too. Each line names the view, the host, the time since it started loading, the
 * view's size and whether it is shown and focused, and what the page reports: `document.visibilityState`,
 * `document.hasFocus()`, its viewport and its user agent. Cookies are named, never their values. Call on the main
 * thread.
 */
object WebViewProbe {
    private const val TAG = "droidtop.WebProbe"

    private const val PAGE = "JSON.stringify({v: document.visibilityState, f: document.hasFocus(), " +
        "w: window.innerWidth, h: window.innerHeight, ua: navigator.userAgent})"

    fun report(which: String, host: String, view: WebView, startedAt: Long, phase: String) {
        val ms = System.currentTimeMillis() - startedAt
        val names = runCatching { CookieManager.getInstance().getCookie("https://$host") }.getOrNull().orEmpty()
            .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.joinToString(",")
        val shown = "view ${view.width}x${view.height} shown=${view.isShown} windowFocus=${view.hasWindowFocus()} " +
            "attached=${view.isAttachedToWindow} windowVisibility=${view.windowVisibility}"
        runCatching {
            view.evaluateJavascript(PAGE) { page ->
                Log.i(TAG, "$which $host +${ms}ms $phase: $shown page=$page title=\"${view.title}\" cookies=[$names]")
            }
        }.onFailure { Log.i(TAG, "$which $host +${ms}ms $phase: $shown cookies=[$names] (page not readable: ${it.message})") }
    }
}

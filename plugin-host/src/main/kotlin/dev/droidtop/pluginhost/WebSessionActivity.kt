package dev.droidtop.pluginhost

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * droidtop's own web view, the one every signed-in web page droidtop shows goes through: a plugin's session
 * (docs/plugin-api.md 3 G3) and a store's own pages (docs/SPEC.md 7g, "A store's own pages"). It runs in droidtop's
 * process, headed with whose session it is and the site the page is on, so a person always sees where they are typing.
 *
 * - **Sign-in** (a plugin): the site's own page; the person signs in there (the plugin never sees the password or the
 *   cookies). Done (or the site setting the plugin's `doneCookie`) ends it, and the cookies of the plugin's declared
 *   sites are handed back to be sealed in the plugin's vault. Back with nothing done is "not signed in".
 * - **Open** (a plugin): a protected link opened with the stored session; the first download the page starts is
 *   captured (its address, the cookies it needs, the user agent and the page it came from) and the view closes.
 * - **Browse** (a store): the store's pages, to look around, claim and buy. Only https pages load. The view reads
 *   nothing of a page: there is no script bridge and no script is run in it, so what a person types into a store's
 *   checkout (a card, an address) goes to the store and nowhere else; droidtop learns only the addresses of the pages
 *   shown ([WebSessionRequest.onPage]), which is how a store's own "thank you" page is recognised.
 *
 * droidtop's cookie store is shared by every web view in its process, so cookies a request puts in
 * ([WebSessionRequest.domains]) are there only while this view is open and are taken out when it closes ([purge]).
 */
class WebSessionActivity : Activity() {
    private var answered = false
    private var webView: WebView? = null
    private var location: TextView? = null
    private var request: WebSessionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val asked = id?.let { requests[it] }
        if (id == null || asked == null || closers[id] == null) {
            finish()
            return
        }
        request = asked
        val browse = asked.mode == WebSessionRequest.Mode.BROWSE
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        asked.domains.forEach { purge(cookies, it) }
        asked.session?.cookies?.forEach { (domain, header) -> inject(cookies, domain, header) }
        cookies.flush()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 20, 24))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = when (asked.mode) {
                WebSessionRequest.Mode.SIGN_IN -> "Sign in for ${asked.label}"
                WebSessionRequest.Mode.OPEN -> "${asked.label}: open in your session"
                WebSessionRequest.Mode.BROWSE -> asked.label
            }
            setTextColor(Color.WHITE)
            textSize = 18f
        })
        location = TextView(this).apply {
            setTextColor(Color.rgb(170, 180, 190))
            textSize = 13f
        }
        titles.addView(location)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (asked.mode == WebSessionRequest.Mode.SIGN_IN) {
            bar.addView(Button(this).apply {
                text = "Done"
                setOnClickListener { finishWith(completed = true, download = null) }
            })
        }
        bar.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finishWith(completed = asked.mode != WebSessionRequest.Mode.SIGN_IN, download = null) }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = when (asked.mode) {
                WebSessionRequest.Mode.SIGN_IN -> "Sign in on the site's own page. Press Done when you are signed in. B goes back a page."
                WebSessionRequest.Mode.OPEN -> "Choose the download on the page; droidtop takes the file into Downloads. B goes back a page."
                WebSessionRequest.Mode.BROWSE ->
                    "${asked.label}'s own pages. Paying happens on them; droidtop never sees or keeps payment details. B goes back a page."
            }
            setTextColor(Color.rgb(170, 180, 190))
            textSize = 12f
            setPadding(dp(16), 0, dp(16), dp(6))
        })

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            asked.session?.userAgent?.let { settings.userAgentString = it }
            cookies.setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Only web pages: an intent:, market: or other app link never leaves droidtop from here. A store's
                    // pages, where people pay, load only over https.
                    val scheme = request.url.scheme?.lowercase()
                    return if (browse) scheme != "https" else scheme != "http" && scheme != "https"
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    location?.text = url?.let { NetScope.hostOf(it) }.orEmpty()
                    if (url != null && browse) asked.onPage?.invoke(url)
                    val done = asked.doneCookie ?: return
                    if (asked.mode != WebSessionRequest.Mode.SIGN_IN) return
                    val set = asked.domains.any { domain ->
                        cookies.getCookie("https://$domain").orEmpty().split(';').any { it.trim().startsWith("$done=") }
                    }
                    if (set) finishWith(completed = true, download = null)
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                if (asked.mode != WebSessionRequest.Mode.OPEN) return@setDownloadListener
                finishWith(
                    completed = true,
                    download = WebSessionDownload(
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        size = contentLength.takeIf { it > 0 },
                        referer = this.url,
                        cookie = cookies.getCookie(url),
                    ),
                )
            }
        }
        webView = view
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        view.requestFocus(View.FOCUS_DOWN)
        view.loadUrl(asked.url)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            val view = webView
            if (view != null && view.canGoBack()) {
                view.goBack()
            } else {
                finishWith(completed = request?.mode != WebSessionRequest.Mode.SIGN_IN, download = null)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Reads the session the page left, takes the request's cookies out of droidtop's store, answers and closes. */
    private fun finishWith(completed: Boolean, download: WebSessionDownload?) {
        if (answered) return
        answered = true
        val asked = request
        val id = intent.getStringExtra(EXTRA_ID)
        val cookies = CookieManager.getInstance()
        val session = asked?.let {
            WebSessions.Stored(
                userAgent = webView?.settings?.userAgentString,
                cookies = it.domains.associateWith { domain -> cookies.getCookie("https://$domain").orEmpty() }.filterValues { v -> v.isNotBlank() },
            )
        }
        asked?.domains?.forEach { purge(cookies, it) }
        cookies.flush()
        id?.let { requests.remove(it) }
        id?.let { closers.remove(it)?.invoke(WebSessionResult(completed, session, download)) }
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        finish()
    }

    override fun onDestroy() {
        // Gone without an answer (Android took the activity away): nothing is kept, and nothing stays in the store.
        if (!answered && isFinishing) finishWith(completed = false, download = null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "dev.droidtop.pluginhost.web.id"

        /** How long a plugin's call waits for the person ([show]); then the answer is "nothing done". */
        private const val TIMEOUT_MS = 10L * 60 * 1000

        private val closers = ConcurrentHashMap<String, (WebSessionResult) -> Unit>()
        private val requests = ConcurrentHashMap<String, WebSessionRequest>()

        /**
         * Shows the web view for [request] and returns at once; [onClosed] gets the answer when the view closes, on the
         * main thread. False when the view could not be started, and then [onClosed] is never called.
         */
        fun open(context: Context, request: WebSessionRequest, onClosed: (WebSessionResult) -> Unit): Boolean {
            val id = UUID.randomUUID().toString()
            closers[id] = onClosed
            requests[id] = request
            val intent = Intent(context, WebSessionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_ID, id)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
            closers.remove(id)
            requests.remove(id)
            return false
        }

        /** Shows the web view for [request] and blocks the calling (binder) thread until it closes or ten minutes pass. */
        fun show(context: Context, request: WebSessionRequest): WebSessionResult {
            val answer = CompletableDeferred<WebSessionResult>()
            if (!open(context, request) { answer.complete(it) }) return WebSessionResult(false, null, null)
            return runBlocking { withTimeoutOrNull(TIMEOUT_MS) { answer.await() } } ?: WebSessionResult(false, null, null)
        }

        /**
         * Takes every cookie droidtop's store holds for [domains] (and their subdomains, at the root path) out of it: a
         * store's web session when the person signs out of that store. Call on the main thread.
         */
        fun forget(domains: List<String>) {
            val cookies = CookieManager.getInstance()
            domains.forEach { purge(cookies, it) }
            cookies.flush()
        }

        /** Puts a stored `name=value; ...` header back for [domain] (and its subdomains). */
        private fun inject(cookies: CookieManager, domain: String, header: String) {
            header.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                cookies.setCookie("https://$domain", "$pair; Domain=.$domain; Path=/; Secure")
            }
        }

        /**
         * Takes every cookie droidtop's store sends to [domain] out of it: each name is expired as a host cookie and as a
         * domain cookie at the root path, which is how sign-in cookies are set. A cookie a site scoped to a deeper path
         * stays; it is sent only to that path of that site.
         */
        private fun purge(cookies: CookieManager, domain: String) {
            val names = cookies.getCookie("https://$domain").orEmpty().split(';')
                .map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
            for (name in names) {
                cookies.setCookie("https://$domain", "$name=; Max-Age=0; Path=/")
                cookies.setCookie("https://$domain", "$name=; Max-Age=0; Path=/; Domain=.$domain")
                cookies.setCookie("https://$domain", "$name=; Max-Age=0; Path=/; Domain=$domain")
            }
        }
    }
}

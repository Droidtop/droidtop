package dev.droidtop.pluginhost

import dev.droidtop.shared.sitecheck.SiteCheck
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
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
 * - **Open** (a plugin): a protected link opened with the stored session; the download the page starts is captured
 *   (its address, the cookies it needs, the user agent and the page it came from) and the view closes. Asked for
 *   several ([WebSessionRequest.maxDownloads], Droidtop/tracker#419), it stays open, says how many it has and closes at
 *   the most asked for or on Done.
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

    /** What an Open view has taken so far, in order, each address once. */
    private val captured = mutableListOf<WebSessionDownload>()

    /** The host link the person followed out of an Open view, for droidtop's resolvers ([WebSessionResult.handoff]). */
    private var handoff: String? = null
    private var takenLine: TextView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // droidtop's colours for a screen it draws with plain views (docs/SPEC.md 7k "Light and dark",
        // Droidtop/tracker#559): the role tokens in use, and the system's light or dark widgets to match them.
        val tokens = dev.droidtop.library.theme.RoleTokens.current(this)
        setTheme(if (tokens.light) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar)
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
        WebSessionCookies.putIn(cookies, asked, siteCookies[id])

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.color("ground"))
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
                WebSessionRequest.Mode.CHECK -> "${asked.label}: open the site"
                WebSessionRequest.Mode.OPEN -> "${asked.label}: open in your session"
                WebSessionRequest.Mode.BROWSE -> asked.label
            }
            setTextColor(tokens.color("onSurface"))
            textSize = 18f
        })
        location = TextView(this).apply {
            setTextColor(tokens.color("onSurfaceMuted"))
            textSize = 13f
        }
        titles.addView(location)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val several = asked.mode == WebSessionRequest.Mode.OPEN && asked.maxDownloads > 1
        if (asked.mode.endsWithDone || several) {
            bar.addView(Button(this).apply {
                text = "Done"
                setOnClickListener { finishWith(completed = true, download = null) }
            })
        }
        // The site's own sign-in page as a full page (Droidtop/tracker#600): F95zone's in-page log-in overlay failed in
        // this view with "Oops! We ran into some problems", and a signed-out page has no download links.
        val signIn = asked.signInUrl?.takeIf { asked.mode != WebSessionRequest.Mode.SIGN_IN && NetScope.isHttps(it) }
        if (signIn != null) {
            bar.addView(Button(this).apply {
                text = "Sign in"
                setOnClickListener { webView?.loadUrl(signIn) }
            })
        }
        bar.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finishWith(completed = !asked.mode.endsWithDone, download = null) }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = when (asked.mode) {
                WebSessionRequest.Mode.SIGN_IN -> "Sign in on the site's own page. Press Done when you are signed in. B goes back a page."
                WebSessionRequest.Mode.CHECK -> if (asked.doneCookie != null) {
                    "Let the site finish its check, then sign in (Sign in above opens the site's own page) to see download links. This closes by itself once you are signed in. B goes back a page."
                } else {
                    "Let the site finish its check and show its page, then press Done. B goes back a page."
                }
                WebSessionRequest.Mode.OPEN -> if (several) {
                    "Choose each download on the page, up to ${asked.maxDownloads}; droidtop takes them into Downloads. Press Done when you have them all. B goes back a page."
                } else {
                    "Choose the download on the page; droidtop takes the file into Downloads. B goes back a page."
                }
                WebSessionRequest.Mode.BROWSE ->
                    "${asked.label}'s own pages. Paying happens on them; droidtop never sees or keeps payment details. B goes back a page."
            }
            setTextColor(tokens.color("onSurfaceMuted"))
            textSize = 12f
            setPadding(dp(16), 0, dp(16), dp(6))
        })
        if (several) {
            takenLine = TextView(this).apply {
                text = "Nothing taken yet."
                setTextColor(tokens.color("onSurface"))
                textSize = 13f
                setPadding(dp(16), 0, dp(16), dp(6))
            }
            root.addView(takenLine)
        }

        val view = WebView(this).apply {
            // The keyboard placement droidtop's own pages follow (docs/SPEC.md 4c), installed by :app.
            webViewSetup?.invoke(this)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // The web view's own user agent, never one kept from before: a browser check compares it with the engine
            // that runs the check, and a kept one from an older WebView made Cloudflare ask again and again
            // (Droidtop/tracker#600). What the view ends with is kept, and droidtop's requests send that one.
            cookies.setAcceptThirdPartyCookies(this, true)
            // What the page reports, in droidtop's log (Droidtop/tracker#600: a site's own error, such as a failed
            // sign-in request, was otherwise invisible on a device).
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.i(LOG_TAG, "console ${message.messageLevel()}: ${message.message()} (${NetScope.hostOf(message.sourceId().orEmpty()).orEmpty()}:${message.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Only web pages: an intent:, market: or other app link never leaves droidtop from here. A store's
                    // pages, where people pay, load only over https.
                    val scheme = request.url.scheme?.lowercase()
                    // A page that leads to a file host droidtop has an API for (a masked link the person just unmasked,
                    // Droidtop/tracker#650) closes there: the host's resolvers serve the link, through the person's
                    // account or a debrid one, where its own page would build the file where no download can reach it.
                    val target = request.url.toString()
                    if (asked.mode == WebSessionRequest.Mode.OPEN && request.isForMainFrame && scheme == "https" &&
                        captured.isEmpty() && dev.droidtop.pluginhost.hosts.HostLinks.served(target)
                    ) {
                        Log.i(LOG_TAG, "${NetScope.hostOf(target)}: handed to droidtop's host resolvers")
                        handoff = target
                        // Not from inside the view's own callback: closing destroys it.
                        view.post { finishWith(completed = true, download = null) }
                        return true
                    }
                    return if (browse) scheme != "https" else scheme != "http" && scheme != "https"
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (response.statusCode >= 400) {
                        val kind = if (request.isForMainFrame) "page" else request.requestHeaders["X-Requested-With"]?.let { "request ($it)" } ?: "request"
                        Log.w(LOG_TAG, "$kind ${request.method} ${NetScope.hostOf(request.url.toString())}${request.url.path.orEmpty()} answered ${response.statusCode}")
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    Log.w(LOG_TAG, "${if (request.isForMainFrame) "page" else "request"} ${NetScope.hostOf(request.url.toString())}${request.url.path.orEmpty()} failed: ${error.description} (${error.errorCode})")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    location?.text = url?.let { NetScope.hostOf(it) }.orEmpty()
                    if (url != null && browse) asked.onPage?.invoke(url)
                    // A browser check is passed once the view shows the site's own page with a pass cookie for it, and the
                    // view closes by itself (Droidtop/tracker#600). Not on a timer or the first page: a challenge's own
                    // page has a check's title, and a clearance cookie alone (`__cf_bm` is set on the challenge) is not a pass.
                    // A clearance still good from before passes at once.
                    val host = url?.let { NetScope.hostOf(it) }
                    // On a site the plugin signs in to, the check also waits for the sign-in ([WebSessionRequest.doneCookie]).
                    val signedIn = asked.doneCookie?.let { done ->
                        asked.domains.any { domain -> cookies.getCookie("https://$domain").orEmpty().split(';').any { it.trim().startsWith("$done=") } }
                    }
                    if (asked.mode == WebSessionRequest.Mode.CHECK && host != null &&
                        (asked.domains.isEmpty() || WebSessions.covers(host, asked.domains)) &&
                        SiteCheck.pagePassed(view.title, cookies.getCookie("https://$host"))
                    ) {
                        if (signedIn != false) {
                            finishWith(completed = true, download = null)
                            return
                        }
                        Log.i(LOG_TAG, "$host: past the check, signed out; waiting for the sign-in")
                    }
                    if (asked.mode == WebSessionRequest.Mode.SIGN_IN && signedIn == true) finishWith(completed = true, download = null)
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                if (asked.mode != WebSessionRequest.Mode.OPEN) return@setDownloadListener
                if (captured.any { it.url == url }) return@setDownloadListener
                // A file the page builds itself (MEGA decrypts in the page and hands over a blob: address) exists only
                // inside this view: no download can fetch it from outside, so it is not taken, and the person is told.
                if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
                    dev.droidtop.library.settings.Notices.flash(
                        "This site builds the file inside the page, so droidtop cannot download it. Go back and pick another mirror",
                        tone = dev.droidtop.library.settings.NoticeTone.Caution,
                        context = this@WebSessionActivity,
                    )
                    return@setDownloadListener
                }
                captured += WebSessionDownload(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    size = contentLength.takeIf { it > 0 },
                    referer = this.url,
                    cookie = cookies.getCookie(url),
                )
                if (captured.size >= asked.maxDownloads) {
                    finishWith(completed = true, download = null)
                } else {
                    takenLine?.text = "Taken ${captured.size} of up to ${asked.maxDownloads}. Press Done when you have them all."
                }
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
                finishWith(completed = request?.mode?.endsWithDone != true, download = null)
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
        val sites = id?.let { siteCookies.remove(it) }
        // The download sites go back to droidtop's vault, as the site left them, and out of the store.
        val (session, renewed) = asked?.let { WebSessionCookies.takeOut(CookieManager.getInstance(), it, sites, webView?.settings?.userAgentString) }
            ?: (null to emptyList())
        DownloadSites.renewLater(applicationContext, renewed)
        id?.let { requests.remove(it) }
        // An Open view answers with everything it took; a single capture is the first of them.
        val taken = (captured + listOfNotNull(download)).distinctBy { it.url }
        id?.let { closers.remove(it)?.invoke(WebSessionResult(completed, session, taken.firstOrNull(), taken, handoff)) }
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
        /**
         * What every droidtop web page gets when it is made: `:app` installs the keyboard placement (WebViewKeys), so
         * a plugin's sign-in types on the companion when the companion has the keyboard. This module cannot see it.
         */
        @Volatile
        var webViewSetup: ((WebView) -> Unit)? = null

        private const val EXTRA_ID = "dev.droidtop.pluginhost.web.id"
        private const val LOG_TAG = "droidtop.WebSession"

        /** How long a plugin's call waits for the person ([show]); then the answer is "nothing done". */
        private const val TIMEOUT_MS = 10L * 60 * 1000

        private val closers = ConcurrentHashMap<String, (WebSessionResult) -> Unit>()
        private val requests = ConcurrentHashMap<String, WebSessionRequest>()

        /** The signed-in download sites' cookies a view puts in, by view: read before it starts, off the main thread. */
        private val siteCookies = ConcurrentHashMap<String, Map<String, String>>()

        /**
         * Shows the web view for [request] and returns at once; [onClosed] gets the answer when the view closes, on the
         * main thread. False when the view could not be started, and then [onClosed] is never called.
         */
        fun open(context: Context, request: WebSessionRequest, onClosed: (WebSessionResult) -> Unit): Boolean {
            val id = UUID.randomUUID().toString()
            closers[id] = onClosed
            requests[id] = request
            runCatching { DownloadSites.cookies(context, except = request.domains) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { siteCookies[id] = it }
            val intent = Intent(context, WebSessionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_ID, id)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
            closers.remove(id)
            requests.remove(id)
            siteCookies.remove(id)
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
            domains.forEach { WebSessionCookies.purge(cookies, it) }
            cookies.flush()
        }
    }
}

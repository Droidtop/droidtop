package dev.droidtop.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.userFacingErrorMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signing in to a store that signs in on its own web page (docs/SPEC.md 7g,
 * "Stores"): the store's page, full screen, with droidtop's own bar over it.
 * droidtop never sees the password: it waits for the page the store returns
 * to after the person signs in, reads the one-time code the store puts there
 * ([StoreSignIn.WebPage]), and hands it to the store to finish the sign-in.
 * One screen for every such store; which one, and how its code is read, is
 * the store's own [StoreLibrary.signIn].
 *
 * This replaces GameNative's GOG, Epic and Amazon OAuth activities; the page
 * addresses and how each store hands its code back are theirs, carried over
 * into each store's sign-in description.
 */
class StoreSignInActivity : AppCompatActivity() {

    private var status by mutableStateOf<String?>(null)
    private var failed by mutableStateOf(false)
    private var webView: WebView? = null
    private val captured = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StoreLibraries.byId(intent.getStringExtra(EXTRA_STORE))
        val signIn = store?.signIn(this) as? StoreSignIn.WebPage
        if (store == null || signIn == null) {
            finish()
            return
        }
        setContent {
            DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            store.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        status?.let { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (failed) TextButton(onClick = { retry(signIn) }) { Text("Try again") }
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context -> signInWebView(context, store, signIn) },
                        onRelease = { view ->
                            view.stopLoading()
                            view.webViewClient = WebViewClient()
                            view.destroy()
                        },
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun signInWebView(context: Context, store: StoreLibrary, signIn: StoreSignIn.WebPage): WebView =
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The stores' sign-in pages need script and storage; nothing on the
            // device is reachable from them.
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    request?.url?.toString()?.let { codeFromAddress(store, signIn, it) } == true

                // A same-site redirect can skip shouldOverrideUrlLoading on a
                // current WebView and arrive here only (GameNative's Amazon
                // sign-in found this), so the address is read here too.
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null && codeFromAddress(store, signIn, url)) view?.stopLoading()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null || view == null || captured.get()) return
                    if (codeFromAddress(store, signIn, url)) return
                    val field = signIn.codeInBody ?: return
                    if (!signIn.isReturnPage(url)) return
                    // The store wrote the code into the page as JSON.
                    view.evaluateJavascript(
                        "(function(){try{var j=JSON.parse(document.body&&document.body.innerText||'{}');return j['$field']||null;}catch(e){return null;}})();",
                    ) { result -> unquote(result)?.let { code -> finishWith(store, code) } }
                }
            }
            loadUrl(signIn.url)
            webView = this
        }

    /** Takes the code off [url] when it is the store's return page; true when it did. */
    private fun codeFromAddress(store: StoreLibrary, signIn: StoreSignIn.WebPage, url: String): Boolean {
        if (!signIn.isReturnPage(url)) return false
        val code = signIn.codeInUrl(url) ?: return false
        finishWith(store, code)
        return true
    }

    private fun finishWith(store: StoreLibrary, code: String) {
        if (!captured.compareAndSet(false, true)) return
        failed = false
        status = "Signing in"
        lifecycleScope.launch {
            // The token exchange writes the store's sign-in file: never on the main thread.
            withContext(Dispatchers.IO) { store.completeSignIn(applicationContext, code) }.fold(
                onSuccess = {
                    StoreChanges.announce(this@StoreSignInActivity)
                    finish()
                },
                onFailure = { exc ->
                    Log.w(TAG, "Signing in to ${store.label} failed", exc)
                    failed = true
                    status = userFacingErrorMessage(exc)
                },
            )
        }
    }

    /** Starts the store's sign-in again after a failure: a fresh page and fresh one-time state. */
    private fun retry(old: StoreSignIn.WebPage) {
        val store = StoreLibraries.byId(intent.getStringExtra(EXTRA_STORE)) ?: return finish()
        val fresh = store.signIn(this) as? StoreSignIn.WebPage ?: old
        captured.set(false)
        failed = false
        status = null
        webView?.loadUrl(fresh.url)
    }

    // B on a pad, and Back, step back through the store's pages first and close only at the first.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else finish()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** evaluateJavascript hands back a JSON string ("\"abc\"") or null. */
    private fun unquote(result: String?): String? {
        val raw = result?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) return raw
        return raw.drop(1).dropLast(1).replace("\\\"", "\"").takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "droidtop.StoreSignIn"
        const val EXTRA_STORE = "dev.droidtop.app.extra.STORE"

        fun intent(context: Context, storeId: String): Intent =
            Intent(context, StoreSignInActivity::class.java)
                .putExtra(EXTRA_STORE, storeId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

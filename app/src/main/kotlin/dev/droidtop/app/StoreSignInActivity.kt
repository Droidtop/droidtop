package dev.droidtop.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.app.ui.QrCode
import dev.droidtop.library.stores.AccountSignInStep
import dev.droidtop.library.stores.StoreAccountSignIn
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
 * Signing in to a store (docs/SPEC.md 7g, "Stores"), one screen for every
 * store, in the form the store's own [StoreLibrary.signIn] asks for:
 *
 * - **A web sign-in** ([StoreSignIn.WebPage]): the store's page, full
 *   screen, with droidtop's own bar over it. droidtop never sees the
 *   password: it waits for the page the store returns to after the person
 *   signs in, reads the one-time code the store puts there, and hands it to
 *   the store to finish the sign-in. This replaces GameNative's GOG, Epic
 *   and Amazon OAuth activities; the page addresses and how each store hands
 *   its code back are theirs, carried over into each store's sign-in.
 * - **A step-by-step sign-in** ([StoreSignIn.Account], Steam): droidtop's own
 *   steps over the store's connection. A QR code first, which the store's
 *   phone app approves (or "Open in the Steam app" on this device), or the
 *   account name and password, then a Steam Guard code or an approval in the
 *   phone app when Steam asks. This replaces droidtop's old Steam sign-in
 *   screen over GameNative's service (SteamLoginActivity).
 */
class StoreSignInActivity : AppCompatActivity() {

    private var status by mutableStateOf<String?>(null)
    private var failed by mutableStateOf(false)
    private var webView: WebView? = null
    private val captured = AtomicBoolean(false)
    private var account: StoreAccountSignIn? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StoreLibraries.byId(intent.getStringExtra(EXTRA_STORE))
        when (val signIn = store?.signIn(this)) {
            is StoreSignIn.WebPage -> showWebSignIn(store, signIn)
            is StoreSignIn.Account -> showAccountSignIn(store, signIn.session)
            else -> finish()
        }
    }

    override fun onDestroy() {
        account?.close()
        account = null
        super.onDestroy()
    }

    private fun showAccountSignIn(store: StoreLibrary, session: StoreAccountSignIn) {
        account = session
        session.start()
        setContent {
            DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                AccountSignIn(
                    label = store.label,
                    session = session,
                    onDone = {
                        StoreChanges.announce(this@StoreSignInActivity)
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun showWebSignIn(store: StoreLibrary, signIn: StoreSignIn.WebPage) {
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

    // B on a pad, and Back: a web sign-in steps back through the store's
    // pages and closes at the first; a step-by-step sign-in goes back to its
    // choices and closes from there.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                val session = account
                val view = webView
                when {
                    session != null -> if (session.step.value is AccountSignInStep.Choose) finish() else session.backToChoices()
                    view != null && view.canGoBack() -> view.goBack()
                    else -> finish()
                }
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

/** The steps of a [StoreSignIn.Account] sign-in, exactly as [StoreAccountSignIn.step] says. */
@Composable
private fun AccountSignIn(label: String, session: StoreAccountSignIn, onDone: () -> Unit, onClose: () -> Unit) {
    val step by session.step.collectAsState()
    LaunchedEffect(step) { if (step is AccountSignInStep.Done) onDone() }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sign in to $label", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        when (val current = step) {
            AccountSignInStep.Connecting -> Busy("Connecting to $label")
            is AccountSignInStep.Choose -> Choices(label, current.failure, session)
            is AccountSignInStep.QrCode -> QrStep(label, current.url, session)
            is AccountSignInStep.Code -> CodeStep(label, current, session)
            AccountSignInStep.ApproveOnPhone -> Busy("Approve this sign-in in the $label app on your phone")
            AccountSignInStep.Working -> Busy("Signing in")
            is AccountSignInStep.Done -> Busy("Signed in${current.account?.let { " as $it" }.orEmpty()}")
        }
        Text(
            "A selects, B goes back",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Busy(line: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(line, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Choices(label: String, failure: String?, session: StoreAccountSignIn) {
    var accountName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    // The primary action is selected on open, so a pad starts there.
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    Text("With a QR code", style = MaterialTheme.typography.titleMedium)
    Text(
        "Scan it with the $label app on your phone and approve. Nothing is typed here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { session.showQrCode() }, modifier = Modifier.focusRequester(first)) { Text("Show a QR code") }
    HorizontalDivider()
    Text("With your account name and password", style = MaterialTheme.typography.titleMedium)
    Text(
        "They go to $label to sign in and are not kept on this device. $label may then ask for a code from its phone app or your e-mail.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = accountName,
        onValueChange = { accountName = it },
        label = { Text("Account name") },
        singleLine = true,
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
    )
    Button(
        enabled = accountName.isNotBlank() && password.isNotEmpty(),
        onClick = {
            session.signInWithPassword(accountName, password)
            password = ""
        },
    ) { Text("Sign in") }
}

@Composable
private fun QrStep(label: String, url: String, session: StoreAccountSignIn) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text("Scan this with the $label app on your phone, then approve the sign-in there.", style = MaterialTheme.typography.bodyLarge)
    QrCode(content = url, size = 240.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The code is Steam's own s.team address; the Steam app on this
        // device, when there is one, claims it and shows its approve screen.
        Button(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Open in the $label app on this device") }
        OutlinedButton(onClick = { session.backToChoices() }) { Text("Back") }
    }
}

@Composable
private fun CodeStep(label: String, step: AccountSignInStep.Code, session: StoreAccountSignIn) {
    var code by remember { mutableStateOf("") }
    Text(
        if (step.sentByEmail) "Enter the code $label sent to your e-mail" else "Enter the code from the $label app on your phone",
        style = MaterialTheme.typography.titleMedium,
    )
    if (step.wrongBefore) Text("That code was not right. Try again.", color = MaterialTheme.colorScheme.error)
    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = code.isNotBlank(), onClick = { session.submitCode(code) }) { Text("Submit") }
        OutlinedButton(onClick = { session.backToChoices() }) { Text("Back") }
    }
}

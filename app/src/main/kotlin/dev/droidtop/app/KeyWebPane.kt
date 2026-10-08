package dev.droidtop.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.droidtop.library.credentials.CredentialVault
import dev.droidtop.library.credentials.web.KeyPageSpec
import dev.droidtop.library.credentials.web.KeyPages
import dev.droidtop.library.scraper.ScraperKeyCheck
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile
import dev.droidtop.shell.gamepad.HintTip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-app key page (docs/SPEC.md 7h, "Reading the key from its page"): the
 * service's own page in a WebView on the left, droidtop's numbered steps on the
 * right that follow the page being shown, and a prompt when the key is on the
 * page. The person signs in and creates the key themselves; droidtop reads only
 * the configured elements on the configured page, shows what it found masked,
 * and stores it only when they press Use. It runs nothing until they press Test.
 *
 * One browser profile per service holds the sign-in; it is deleted when the
 * pane closes unless "Stay signed in" is on (off by default). There is no
 * JavaScript bridge: the pane runs a read-only script on the matching address
 * and takes its answer, so no page script can call back into the app.
 */
internal object KeyWebProfiles {
    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun name(service: ScraperKeyService) = "droidtop_key_${service.name.lowercase()}"

    fun stayKey(service: ScraperKeyService) = "droidtop_${service.name.lowercase()}_stay_signed_in"

    fun stay(context: Context, service: ScraperKeyService): Boolean =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(stayKey(service), false)

    fun delete(service: ScraperKeyService) {
        runCatching { ProfileStore.getInstance().deleteProfile(name(service)) }
    }
}

private enum class PaneNotice { NONE, BLOCKED, REFUSED }

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun KeyWebPane(
    service: ScraperKeyService,
    page: KeyPageSpec,
    panelFocus: Boolean,
    onOtherWays: () -> Unit,
    onFallback: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf(PaneNotice.NONE) }
    var stay by remember { mutableStateOf(KeyWebProfiles.stay(context, service)) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var stored by remember { mutableIntStateOf(0) }
    var storedMasks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Found on the page and not yet used or ignored; held in memory only.
    val found = remember { mutableStateMapOf<String, String>() }
    val ignored = remember { mutableStateOf(setOf<String>()) }
    val panelRequester = remember { FocusRequester() }

    // A leftover profile (a crash, or "Stay signed in" turned off since) starts clean.
    val prepared = remember {
        if (!stay) KeyWebProfiles.delete(service)
        runCatching { ProfileStore.getInstance().getOrCreateProfile(KeyWebProfiles.name(service)) }.isSuccess
    }
    LaunchedEffect(prepared) { if (!prepared) onFallback() }

    LaunchedEffect(stored) {
        storedMasks = withContext(Dispatchers.IO) {
            service.read(context).filterValues { it.isNotBlank() }.mapValues { (id, value) ->
                if (service.fields.first { it.id == id }.secret) CredentialVault.mask(value) else value
            }
        }
    }

    LaunchedEffect(panelFocus) {
        if (panelFocus) runCatching { panelRequester.requestFocus() } else webView?.requestFocus()
    }

    // Read the page every second while it is the capture page; the script only looks up
    // the configured elements and the address is checked again when the answer arrives.
    LaunchedEffect(webView) {
        val capture = page.capture ?: return@LaunchedEffect
        val script = KeyPages.script(capture)
        while (true) {
            delay(1_000)
            val view = webView ?: continue
            if (!KeyPages.captureAllowed(page, view.url)) continue
            view.evaluateJavascript(script) { raw ->
                if (!KeyPages.captureAllowed(page, view.url)) return@evaluateJavascript
                KeyPages.parseResult(capture, raw).forEach { (id, value) ->
                    if (value !in ignored.value && found[id] != value) found[id] = value
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let {
                it.stopLoading()
                it.webViewClient = WebViewClient()
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            found.clear()
            if (!KeyWebProfiles.stay(context, service)) KeyWebProfiles.delete(service)
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (prepared) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        val bound = runCatching { WebViewCompat.setProfile(this, KeyWebProfiles.name(service)) }.isSuccess
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportMultipleWindows(false)
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }
                        isFocusable = true
                        isFocusableInTouchMode = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (request == null || !request.isForMainFrame) return false
                                if (KeyPages.canLoad(page, request.url.toString())) return false
                                // Another provider's sign-in (or any other site) does not load in this pane.
                                notice = PaneNotice.BLOCKED
                                return true
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                currentUrl = url
                                step = KeyPages.stepIndex(page, url, step)
                            }

                            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                if (request?.isForMainFrame == true && errorResponse?.statusCode == 403) notice = PaneNotice.REFUSED
                            }
                        }
                        if (bound) {
                            loadUrl(page.startUrl)
                            webView = this
                        } else {
                            notice = PaneNotice.BLOCKED
                        }
                    }
                },
            )
        }
        Column(
            modifier = Modifier.width(280.dp).fillMaxHeight().focusRequester(panelRequester).focusGroup()
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(service.title, style = MaterialTheme.typography.titleLarge)
            HintTip(service.credit) { Text(service.gets, style = MaterialTheme.typography.labelLarge) }
            page.steps.forEachIndexed { index, item ->
                HintTip(item.hint) {
                    Text(
                        "${index + 1}  ${item.label}",
                        style = if (index == step) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        color = if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (notice != PaneNotice.NONE) {
                HintTip("This site would not sign in inside droidtop. Open it in the browser and send the key from your phone.") {
                    Button(onClick = onFallback) { Text("Use browser and phone") }
                }
            }
            service.fields.forEach { field ->
                val candidate = found[field.id]
                if (candidate != null) {
                    Text("${field.label}: found ${CredentialVault.mask(candidate)}", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            found.remove(field.id)
                            scope.launch {
                                withContext(Dispatchers.IO) { service.write(context, mapOf(field.id to candidate)) }
                                stored++
                                result = null
                            }
                        }) { Text("Use") }
                        TextButton(onClick = {
                            ignored.value = ignored.value + candidate
                            found.remove(field.id)
                        }) { Text("Ignore") }
                    }
                } else {
                    storedMasks[field.id]?.let { Text("${field.label}: $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            result?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Button(
                enabled = !testing,
                onClick = {
                    testing = true
                    result = "Testing"
                    scope.launch {
                        result = withContext(Dispatchers.IO) { ScraperKeyCheck.test(context, service) }
                        testing = false
                    }
                },
            ) { Text("Test") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HintTip("Keeps your sign-in on this page for next time. Off: it is deleted when you close this.") {
                    Text("Stay signed in", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = stay, onCheckedChange = {
                    stay = it
                    PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(KeyWebProfiles.stayKey(service), it)
                })
            }
            TextButton(onClick = onOtherWays) { Text("Other ways") }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

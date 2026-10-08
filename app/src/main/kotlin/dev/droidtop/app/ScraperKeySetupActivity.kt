package dev.droidtop.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.app.ui.QrCode
import dev.droidtop.library.credentials.handoff.HandoffHttp
import dev.droidtop.library.credentials.handoff.HandoffServer
import dev.droidtop.library.credentials.handoff.HandoffSession
import dev.droidtop.library.credentials.web.KeyPageSpec
import dev.droidtop.library.credentials.web.KeyPages
import dev.droidtop.library.scraper.ScraperKeyCheck
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.scraper.ScraperKeyState
import dev.droidtop.shell.gamepad.HintTip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The guided setup for a scraper source that needs the person's own
 * credential (docs/SPEC.md 7h, "Supplying your own keys"). Three ways in, one
 * screen: the service's own page in a browser pane that reads the key off the
 * page when the person confirms ([KeyWebPane]); a QR code and button that open
 * the official page elsewhere; and a one-time page on the local network so the
 * key is pasted from a phone or PC instead of typed on a gamepad. Fields also
 * take the clipboard. Every path ends in the same encrypted store, and nothing
 * is tested until Test is pressed. Opened from the source's row under
 * Settings > Accounts and sources.
 */
class ScraperKeySetupActivity : AppCompatActivity() {
    private val panelFocus = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = ScraperKeyService.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SERVICE) }
        if (service == null) {
            finish()
            return
        }
        setContent {
            DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                Scaffold { padding ->
                    var page by remember { mutableStateOf<KeyPageSpec?>(null) }
                    var loaded by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        page = withContext(Dispatchers.IO) {
                            runCatching {
                                KeyPages.parse(assets.open("key-pages.json").bufferedReader().use { it.readText() })[service.name]
                            }.getOrNull()
                        }
                        loaded = true
                    }
                    if (loaded) {
                        SetupHost(service, page, panelFocus.value, onClose = { finish() }, modifier = Modifier.fillMaxSize().padding(padding))
                    }
                }
            }
        }
    }

    // L1 and R1 move between the page and the steps beside it; the page otherwise swallows the pad.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1)
        ) {
            panelFocus.value = !panelFocus.value
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_SERVICE = "service"

        fun intent(context: Context, service: ScraperKeyService): Intent =
            Intent(context, ScraperKeySetupActivity::class.java).putExtra(EXTRA_SERVICE, service.name)
    }
}

private enum class SetupMode { WEB, MANUAL }

@Composable
private fun SetupHost(
    service: ScraperKeyService,
    page: KeyPageSpec?,
    panelFocus: Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val paneAvailable = page != null && KeyWebProfiles.supported()
    var mode by remember { mutableStateOf(if (paneAvailable) SetupMode.WEB else SetupMode.MANUAL) }
    // Set when the provider refused the pane: the manual screen opens the browser and starts the phone page.
    var fellBack by remember { mutableStateOf(false) }
    if (mode == SetupMode.WEB && page != null) {
        KeyWebPane(
            service = service,
            page = page,
            panelFocus = panelFocus,
            onOtherWays = { mode = SetupMode.MANUAL },
            onFallback = {
                fellBack = true
                mode = SetupMode.MANUAL
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onClose = onClose,
        )
    } else {
        Column(
            modifier = modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ManualSetup(
                service = service,
                autoPhone = fellBack,
                browserPaneAvailable = paneAvailable,
                onBrowserPane = { mode = SetupMode.WEB },
            )
        }
    }
}

private class HandoffRun(val session: HandoffSession, val server: HandoffServer, val url: String)

@Composable
private fun ManualSetup(service: ScraperKeyService, autoPhone: Boolean, browserPaneAvailable: Boolean, onBrowserPane: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    // The fields buffer here and are stored on Test; the vault is read off the main thread.
    val buffer = remember { mutableStateMapOf<String, String>() }
    var handoff by remember { mutableStateOf<HandoffRun?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        state = withContext(Dispatchers.IO) { ScraperKeyCheck.state(context, service) }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { buffer.putAll(service.read(context)) }
        refresh()
        loaded = true
    }

    fun stopPhone() {
        handoff?.server?.stop()
        handoff = null
    }

    fun startPhone() {
        stopPhone()
        phoneError = null
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                val host = HandoffServer.lanAddress() ?: return@withContext null
                val session = HandoffSession(service.handoffFields(), System::currentTimeMillis)
                val server = HandoffServer(session, HandoffHttp(session, "${service.title} key"))
                val port = runCatching { server.start() }.getOrNull() ?: return@withContext null
                HandoffRun(session, server, HandoffServer.url(host, port, session.token))
            }
            if (started == null) phoneError = "No network" else handoff = started
        }
    }
    LaunchedEffect(autoPhone) { if (autoPhone) startPhone() }
    DisposableEffect(Unit) { onDispose { handoff?.server?.stop() } }

    // Ends the page when it expires or is closed; the server stops itself, the screen follows.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(handoff) {
        while (handoff != null) {
            delay(500)
            tick++
            val current = handoff ?: break
            if (!current.session.isLive()) stopPhone()
        }
    }

    Text(service.title, style = MaterialTheme.typography.headlineMedium)
    HintTip(service.credit) {
        Text(result ?: state, style = MaterialTheme.typography.titleMedium)
    }
    Text(service.gets, style = MaterialTheme.typography.labelLarge)
    if (browserPaneAvailable) {
        Button(onClick = onBrowserPane) { Text("Open the key page here") }
    }
    service.steps.forEachIndexed { index, step ->
        Text("${index + 1}  $step", style = MaterialTheme.typography.bodyLarge)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        QrCode(content = service.url, size = 180.dp)
        Button(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open on this device") }
    }

    if (loaded) {
        service.fields.forEach { field ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = buffer[field.id].orEmpty(),
                    onValueChange = { buffer[field.id] = it.trim() },
                    label = { Text(field.label) },
                    singleLine = true,
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboardText(context)?.let { buffer[field.id] = it } }) { Text("Paste") }
            }
        }
    }

    val current = handoff
    if (current == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HintTip("Shows a code. Scan it with a phone or PC on the same network and paste the key there.") {
                Button(onClick = { startPhone() }) { Text("Use my phone") }
            }
            phoneError?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    } else {
        PhonePanel(
            service = service,
            handoff = current,
            tick = tick,
            onSave = { values ->
                scope.launch {
                    withContext(Dispatchers.IO) { service.write(context, values) }
                    values.forEach { (id, value) -> buffer[id] = value }
                    stopPhone()
                    result = null
                    refresh()
                }
            },
            onCancel = { stopPhone() },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !testing && loaded, onClick = {
            testing = true
            result = "Testing"
            scope.launch {
                val line = withContext(Dispatchers.IO) {
                    service.write(context, buffer.toMap())
                    ScraperKeyCheck.test(context, service)
                }
                refresh()
                result = line
                testing = false
            }
        }) { Text("Test") }
        if (state != ScraperKeyState.NOT_SET && state != ScraperKeyState.NO_ACCOUNT && state != ScraperKeyState.BUILT_IN) {
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { service.write(context, service.fields.associate { it.id to "" }) }
                    buffer.clear()
                    result = null
                    refresh()
                }
            }) { Text("Clear") }
        }
    }
}

@Composable
private fun PhonePanel(
    service: ScraperKeyService,
    handoff: HandoffRun,
    tick: Int,
    onSave: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    // tick only forces a redraw of the countdown and of a freshly received page.
    val received = remember(tick) { handoff.session.received() }
    if (received == null) {
        val seconds = (handoff.session.remainingMs() / 1000).toInt()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(content = handoff.url, size = 200.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onCancel) { Text("Cancel") }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Received", style = MaterialTheme.typography.titleMedium)
            service.fields.forEach { field ->
                Text(
                    "${field.label}: ${HandoffSession.maskForReview(received[field.id].orEmpty(), field.secret)}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSave(received) }) { Text("Save") }
                TextButton(onClick = onCancel) { Text("Discard") }
            }
        }
    }
}

/** The clipboard's text, trimmed; null when it holds none. */
private fun clipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

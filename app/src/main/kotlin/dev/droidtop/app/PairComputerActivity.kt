package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.app.ui.QrCode
import dev.droidtop.library.credentials.handoff.HandoffServer
import dev.droidtop.net.peer.AgentNative
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import dev.droidtop.net.peer.DeviceIdentity
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Pairing a computer (docs/SPEC.md 7o "Computers"): shows a 6-digit code to
 * type into droidtop-agent on the computer (`droidtop-agent pair 123456`) and
 * a QR code of the same invitation for a computer or phone with a camera. Or
 * the other way round, for a computer that cannot reach this device: the
 * person types the address and code `droidtop-agent pair` shows there.
 * While this screen is open, and only then, droidtop listens for the computer
 * on the local network; the code works once, and three wrong ones end the
 * attempt. Opened from Settings > Accounts and sources > Computers.
 */
class PairComputerActivity : AppCompatActivity() {
    private var multicast: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                Column(Modifier.fillMaxSize().groundBackground()) {
                    Box(Modifier.weight(1f)) {
                        PairScreen(onDone = { finish() }, holdMulticast = ::holdMulticast)
                    }
                    HintRow(bindings = listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, "Back")))
                }
            }
        }
    }

    /** The computer finds this device by a broadcast; some Wi-Fi drivers drop those without the lock. */
    private fun holdMulticast() {
        if (multicast != null) return
        multicast = runCatching {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("droidtop-pairing").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        // Ends the listener the moment the screen goes: nothing listens once it is closed.
        Thread { AgentNative.call("pair_cancel") }.start()
        runCatching { multicast?.release() }
        multicast = null
        super.onDestroy()
    }

    companion object {
        /** The page where droidtop-agent's builds are. */
        const val AGENT_RELEASES = "https://github.com/Droidtop/droidtop-agent/releases/latest"

        fun intent(context: Context): Intent = Intent(context, PairComputerActivity::class.java)
    }
}

private sealed interface PairState {
    data object Starting : PairState
    data class Showing(val code: String, val uri: String) : PairState
    data class Paired(val name: String) : PairState
    data class Failed(val reason: String) : PairState
}

@Composable
private fun PairScreen(onDone: () -> Unit, holdMulticast: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<PairState>(PairState.Starting) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        state = PairState.Starting
        holdMulticast()
        state = withContext(Dispatchers.IO) {
            if (!AgentNative.available) return@withContext PairState.Failed("This build of droidtop has no computer sync library.")
            val seed = DeviceIdentity.seed(context) ?: return@withContext PairState.Failed("This device's key could not be made.")
            val started = AgentNative.call(
                "pair_start",
                JSONObject().put("seed", seed).put("name", Computers.deviceName(context)).put("address", HandoffServer.lanAddress() ?: ""),
            )
            AgentNative.failure(started)?.let { return@withContext PairState.Failed(it) }
            PairState.Showing(started.optString("code"), started.optString("uri"))
        }
        if (state !is PairState.Showing) return@LaunchedEffect
        val waited = withContext(Dispatchers.IO) {
            val reply = AgentNative.call("pair_wait", JSONObject().put("timeout_ms", WAIT_MS))
            AgentNative.failure(reply)?.let { return@withContext PairState.Failed(it.replaceFirstChar { c -> c.uppercase() }) }
            PairState.Paired(keep(context, reply))
        }
        // Paired the other way meanwhile: that cancelled this wait, and its failure is not news.
        if (state is PairState.Paired) return@LaunchedEffect
        state = waited
        if (waited is PairState.Paired) {
            delay(SHOW_PAIRED_MS)
            onDone()
        }
    }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pair a computer", style = MaterialTheme.typography.headlineSmall)
        when (val s = state) {
            PairState.Starting -> Text("Getting a code…", style = MaterialTheme.typography.bodyLarge)
            is PairState.Showing -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("On the computer, run droidtop-agent and type:", style = MaterialTheme.typography.bodyLarge)
                    Text("droidtop-agent pair ${s.code.chunked(3).joinToString(" ")}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    Text(s.code.chunked(3).joinToString(" "), fontSize = 56.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "Keep this screen open until the computer says it is paired. Both have to be on the same network. " +
                            "No droidtop-agent yet? It is at github.com/Droidtop/droidtop-agent.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                QrCode(content = s.uri, size = 220.dp)
            }
            is PairState.Paired -> Text("Paired with ${s.name}.", style = MaterialTheme.typography.titleLarge)
            is PairState.Failed -> {
                Text(s.reason, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Get a new code") }
            }
        }
        // The other way round, for a computer that cannot reach this device (an emulator, a guest network):
        // `droidtop-agent pair` on the computer shows its address and a code, and this device connects.
        if (state !is PairState.Paired) {
            Text("Use a code from the computer", style = MaterialTheme.typography.titleMedium)
            Text(
                "If the computer cannot find this device, run droidtop-agent pair on the computer with nothing after it, and type the address and code it shows.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text("Computer's address, such as 192.168.1.20:47612") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            connecting?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    connecting = "Pairing…"
                    scope.launch {
                        // The name of the computer paired with, or why not.
                        val (paired, failure) = withContext<Pair<String?, String?>>(Dispatchers.IO) {
                            val seed = DeviceIdentity.seed(context) ?: return@withContext null to "This device's key could not be made."
                            val reply = AgentNative.call(
                                "pair_connect",
                                JSONObject().put("seed", seed).put("name", Computers.deviceName(context)).put("address", address).put("code", typed),
                            )
                            AgentNative.failure(reply)?.let { return@withContext null to it.replaceFirstChar { c -> c.uppercase() } }
                            val name = keep(context, reply)
                            // This screen's own code is not needed any more.
                            AgentNative.call("pair_cancel")
                            name to null
                        }
                        connecting = failure
                        if (paired != null) {
                            state = PairState.Paired(paired)
                            delay(SHOW_PAIRED_MS)
                            onDone()
                        }
                    }
                },
                enabled = connecting != "Pairing…" && address.isNotBlank() && typed.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pair with this computer") }
        }
    }
}

/** Keeps the computer a pairing reply names, and returns its name. Writes a small file: never on the main thread. */
private fun keep(context: Context, reply: JSONObject): String {
    val name = reply.optString("name").ifBlank { "Computer" }
    Computers.put(
        context,
        Computer(
            id = reply.optString("peer"),
            name = name,
            addresses = listOfNotNull(reply.optString("address").takeIf { it.isNotBlank() }),
            pairedAtMs = System.currentTimeMillis(),
        ),
    )
    return name
}

private const val WAIT_MS = 10 * 60 * 1000L
private const val SHOW_PAIRED_MS = 1500L

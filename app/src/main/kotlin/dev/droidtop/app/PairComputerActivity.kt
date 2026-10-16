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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.droidtop.net.peer.PairedDevices
import dev.droidtop.net.peer.DeviceIdentity
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import kotlinx.coroutines.Dispatchers
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
 *
 * The same screen pairs two handhelds running droidtop ([EXTRA_HANDHELD], Droidtop/tracker#556): one shows its code
 * and its address, the other types both under "Use a code from the other handheld". The agent core runs the same
 * pairing either way and says which kind of device answered.
 */
class PairComputerActivity : AppCompatActivity() {
    private var multicast: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                // This screen has no Surface, whose job is to set the text colour: without it every Text is
                // black, on the ground's black (Droidtop/tracker#527).
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().groundBackground()) {
                        Box(Modifier.weight(1f)) {
                            PairScreen(handheld = intent.getBooleanExtra(EXTRA_HANDHELD, false), onDone = { finish() }, holdMulticast = ::holdMulticast)
                        }
                        HintRow(bindings = listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, "Back")))
                    }
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
        // The pad's cancel button (the face-button swap applied, FaceKeys), Escape or Back.
        if (event.action == KeyEvent.ACTION_UP && org.pocketworkstation.pckeyboard.FaceKeys.isCancel(event.keyCode)) {
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

        /** Pairing another handheld running droidtop rather than a computer: the same pairing, worded for it. */
        const val EXTRA_HANDHELD = "dev.droidtop.extra.PAIR_HANDHELD"

        fun intent(context: Context, handheld: Boolean = false): Intent =
            Intent(context, PairComputerActivity::class.java).putExtra(EXTRA_HANDHELD, handheld)
    }
}

private sealed interface PairState {
    data object Starting : PairState
    /** [at] is this device's address and pairing port, which another handheld types: it has no broadcast to find it by. */
    data class Showing(val code: String, val uri: String, val at: String?) : PairState
    data class Paired(val computer: Computer) : PairState
    data class Failed(val reason: String) : PairState
}

@Composable
private fun PairScreen(handheld: Boolean, onDone: () -> Unit, holdMulticast: () -> Unit) {
    val other = if (handheld) "handheld" else "computer"
    val context = LocalContext.current
    var state by remember { mutableStateOf<PairState>(PairState.Starting) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        state = PairState.Starting
        holdMulticast()
        state = withContext(Dispatchers.IO) {
            if (!AgentNative.available) return@withContext PairState.Failed("This build of droidtop has no computer sync library.")
            val seed = DeviceIdentity.seed(context) ?: return@withContext PairState.Failed("This device's key could not be made.")
            val lan = HandoffServer.lanAddress()
            val started = AgentNative.call(
                "pair_start",
                JSONObject().put("seed", seed).put("name", Computers.deviceName(context)).put("address", lan ?: ""),
            )
            AgentNative.failure(started)?.let { return@withContext PairState.Failed(it) }
            val port = started.optInt("port")
            PairState.Showing(started.optString("code"), started.optString("uri"), lan?.takeIf { port > 0 }?.let { "$it:$port" })
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
    }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pair a $other", style = MaterialTheme.typography.headlineSmall)
        when (val s = state) {
            PairState.Starting -> Text("Getting a code…", style = MaterialTheme.typography.bodyLarge)
            is PairState.Showing -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (handheld) {
                        Text(
                            "On the other handheld, open Settings > Computers > Pair a handheld, and under Use a code from the other handheld type this address and code:",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(s.at ?: "This device is on no network", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                        Text(s.code.chunked(3).joinToString(" "), fontSize = 56.sp, fontFamily = FontFamily.Monospace)
                        Text("Keep this screen open until the other handheld says it is paired. Both have to be on the same network.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("On the computer, open droidtop-agent, choose Pair a handheld and type:", style = MaterialTheme.typography.bodyLarge)
                        Text(s.code.chunked(3).joinToString(" "), fontSize = 56.sp, fontFamily = FontFamily.Monospace)
                        Text("(or at a command line: droidtop-agent pair ${s.code})", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Keep this screen open until the computer says it is paired. Both have to be on the same network. " +
                                "No droidtop-agent yet? It is at github.com/Droidtop/droidtop-agent.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                QrCode(content = s.uri, size = 220.dp)
            }
            is PairState.Paired -> if (s.computer.handheld) PairedHandheld(s.computer, onDone) else PairedChoices(s.computer, onDone)
            is PairState.Failed -> {
                Text(s.reason, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Get a new code") }
            }
        }
        // The other way round, for a computer that cannot reach this device (an emulator, a guest network):
        // `droidtop-agent pair` on the computer shows its address and a code, and this device connects.
        if (state !is PairState.Paired) {
            Text("Use a code from the other $other", style = MaterialTheme.typography.titleMedium)
            Text(
                if (handheld) {
                    "Or the other way round: open Pair a handheld on the other handheld, and type the address and code it shows."
                } else {
                    "If the computer cannot find this device, choose Show a code in droidtop-agent's Pair a handheld on the computer, and type the address and code it shows."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text("The $other's address, such as 192.168.1.20:47612") },
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
                        // The computer paired with, or why not.
                        val (paired, failure) = withContext<Pair<Computer?, String?>>(Dispatchers.IO) {
                            val seed = DeviceIdentity.seed(context) ?: return@withContext null to "This device's key could not be made."
                            val reply = AgentNative.call(
                                "pair_connect",
                                JSONObject().put("seed", seed).put("name", Computers.deviceName(context)).put("address", address).put("code", typed),
                            )
                            AgentNative.failure(reply)?.let { return@withContext null to it.replaceFirstChar { c -> c.uppercase() } }
                            val computer = keep(context, reply)
                            // This screen's own code is not needed any more.
                            AgentNative.call("pair_cancel")
                            computer to null
                        }
                        connecting = failure
                        if (paired != null) state = PairState.Paired(paired)
                    }
                },
                enabled = connecting != "Pairing…" && address.isNotBlank() && typed.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pair with this $other") }
        }
    }
}

/**
 * Keeps the device a pairing reply names, with the kind it said it is and its certificate's fingerprint. A computer's
 * games and apps stay out of the library until the person turns them on. Writes a small file: never on the main
 * thread. Then the plugins that work with paired devices hear of it ([PairedDevices.changed], on its own thread: it
 * may reach every paired device).
 */
private fun keep(context: Context, reply: JSONObject): Computer {
    val handheld = reply.optString("kind") == Computers.KIND_HANDHELD
    val computer = Computer(
        id = reply.optString("peer"),
        name = reply.optString("name").ifBlank { if (handheld) "Handheld" else "Computer" },
        addresses = listOfNotNull(reply.optString("address").takeIf { it.isNotBlank() }),
        pairedAtMs = System.currentTimeMillis(),
        kind = if (handheld) Computers.KIND_HANDHELD else Computers.KIND_COMPUTER,
        fingerprint = reply.optString("fingerprint").takeIf { it.isNotBlank() },
    )
    Computers.put(context, computer)
    val appContext = context.applicationContext
    Thread({ runCatching { PairedDevices.changed(appContext) } }, "devices-changed").start()
    return computer
}

/** A handheld paired: nothing to choose (handhelds share no library yet), only what pairing it does. */
@Composable
private fun PairedHandheld(device: Computer, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paired with ${device.name}.", style = MaterialTheme.typography.titleLarge)
        Text(
            "Plugins that work with your devices, such as Syncthing, can now find it, once you allow them to see your paired devices.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

/**
 * The paired screen asks, once and in plain words, whether the computer's
 * games and apps should show in this device's library (docs/SPEC.md 7o,
 * "Library"). Both start off; leaving without an answer keeps them off, and
 * the computer's page in Settings has the same switches.
 */
@Composable
private fun PairedChoices(computer: Computer, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(false) }
    fun save() {
        val (g, a) = games to apps
        scope.launch {
            withContext(Dispatchers.IO) { Computers.setShown(context, computer.id, games = g, apps = a) }
            dev.droidtop.app.settings.ComputersCatalog.refreshLibrary(context)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paired with ${computer.name}.", style = MaterialTheme.typography.titleLarge)
        Text("Saves sync with it when a game starts and ends. Should its games and programs also show here?", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show its games in my library (the ones not on this device)", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = games, onCheckedChange = {
                games = it
                save()
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show its programs in Apps", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = apps, onCheckedChange = {
                apps = it
                save()
            })
        }
        Text("You can change both later in Settings > Computers > ${computer.name}.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

private const val WAIT_MS = 10 * 60 * 1000L

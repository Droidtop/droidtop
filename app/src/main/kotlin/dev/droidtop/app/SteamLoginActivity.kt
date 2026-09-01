package dev.droidtop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.ui.screen.login.QrCodeImage
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.library.GamesRoots
import dev.droidtop.runtime.windows.SteamAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * droidtop's OWN Steam surface (per direction: gamenative's UI stays out
 * of the way; droidtop layers its own UI over gamenative's services).
 * One screen: sign in (QR scanned from another device, QR handed to the
 * Steam app on THIS device via its own s.team challenge link, or a
 * credentials form the USER types into), then the owned library with
 * per-game download actions. Downloaded games flow into droidtop's own
 * library scan, where engine detection runs and a Ren'Py/RPG Maker/
 * KiriKiri title launches through enginehost like any other engine game.
 */
class SteamLoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SteamAccess.ensureRunning(this)
        setContent {
            DroidtopTheme(darkTheme = true) {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Steam", style = MaterialTheme.typography.headlineMedium)
                        SteamScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamScreen() {
    val phase by SteamAccess.phase.collectAsState()
    when (val current = phase) {
        SteamAccess.Phase.Idle, SteamAccess.Phase.Connecting -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(12.dp))
                Text("Connecting to Steam…")
            }
        }
        SteamAccess.Phase.Connected -> SignInChoices()
        is SteamAccess.Phase.QrReady -> QrPanel(current.challengeUrl)
        is SteamAccess.Phase.AwaitingCode -> TwoFactorPanel(current)
        SteamAccess.Phase.AwaitingDeviceConfirm -> {
            Text("Approve this sign-in in your Steam Mobile app.", style = MaterialTheme.typography.bodyLarge)
            CircularProgressIndicator()
        }
        is SteamAccess.Phase.LoggedIn -> LibraryPanel(current.username)
        is SteamAccess.Phase.Failed -> {
            Text(
                "Sign-in failed: ${current.message ?: "unknown"}",
                color = MaterialTheme.colorScheme.error,
            )
            SignInChoices()
        }
    }
}

@Composable
private fun SignInChoices() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text("Sign in with QR", style = MaterialTheme.typography.titleMedium)
    Text(
        "Shows a QR code you approve with the Steam Mobile app, on this device or another. Nothing is typed here at all.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { SteamAccess.startQrLogin() }) { Text("Show QR code") }
    HorizontalDivider()
    Text("Or sign in with your account name and password", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Account name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = username.isNotBlank() && password.isNotBlank(),
        onClick = { SteamAccess.loginWithCredentials(username, password) },
    ) { Text("Sign in") }
}

@Composable
private fun QrPanel(challengeUrl: String) {
    val context = LocalContext.current
    Text("Scan with the Steam Mobile app, or open it right here:", style = MaterialTheme.typography.bodyLarge)
    QrCodeImage(content = challengeUrl, size = 260.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The challenge URL is Steam's own s.team link; the Steam app
        // on this device claims it and shows its native approve screen.
        Button(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(challengeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Open in Steam app") }
        OutlinedButton(onClick = { SteamAccess.cancelQrLogin() }) { Text("Cancel") }
    }
}

@Composable
private fun TwoFactorPanel(state: SteamAccess.Phase.AwaitingCode) {
    var code by remember { mutableStateOf("") }
    Text(
        if (state.viaEmail) "Enter the code Steam emailed you" else "Enter the code from your Steam Mobile app",
        style = MaterialTheme.typography.titleMedium,
    )
    if (state.previousIncorrect) {
        Text("That code wasn't right, try again.", color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Guard code") }, singleLine = true)
    Button(enabled = code.isNotBlank(), onClick = { SteamAccess.submitTwoFactorCode(code) }) { Text("Submit") }
}

@Composable
private fun LibraryPanel(username: String?) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var games by remember { mutableStateOf<List<SteamAccess.OwnedGame>>(emptyList()) }
    val progress = remember { mutableStateMapOf<Int, Float>() }
    var useExternal by remember { mutableStateOf(PrefManager.useExternalStorage) }
    // Whether a Wine environment exists at all. A downloaded Windows
    // game with nowhere to run is a wasted download, so this is checked
    // before the first one starts rather than at launch time.
    var provisioned by remember { mutableStateOf<Boolean?>(null) }
    var setupStatus by remember { mutableStateOf<String?>(null) }
    var setupRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) { games = SteamAccess.ownedGames(context) }
    LaunchedEffect(refresh, setupRunning) {
        provisioned = withContext(Dispatchers.IO) {
            dev.droidtop.library.PcGameRuntimeRegistry.runtime?.isProvisioned == true
        }
    }

    fun runSetup() {
        if (setupRunning) return
        val runtime = dev.droidtop.library.PcGameRuntimeRegistry.runtime
        if (runtime == null) {
            setupStatus = "This build has no Windows runtime registered."
            return
        }
        setupRunning = true
        setupStatus = "Setting up the Windows environment..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runtime.provision(dev.droidtop.library.GamesRoots.current(context)) { status ->
                    setupStatus = status
                }
            }
            setupStatus = result.detail
            setupRunning = false
        }
    }

    if (provisioned == false) {
        // Stated once, above the library, rather than as a failure after
        // somebody has already waited for a download.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Windows games need a Wine environment before they can run.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                setupStatus ?: "Downloading and installing it takes several hundred megabytes, once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(enabled = !setupRunning, onClick = { runSetup() }) {
                Text(if (setupRunning) "Setting up..." else "Set up Windows games")
            }
        }
    } else if (setupStatus != null) {
        Text(setupStatus!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Text(
        "Signed in${username?.let { " as $it" } ?: ""}. ${games.size} games known" +
            " (the list fills in as Steam metadata arrives).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Install location decides who else can SEE the files: enginehost is
    // a separate app, so an engine game it should launch must land on
    // shared storage, not droidtop's private data dir.
    Text("Install new games to:", style = MaterialTheme.typography.titleSmall)
    val gamesRoot = remember { GamesRoots.current(context).firstOrNull() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = !useExternal, onClick = {
            PrefManager.useExternalStorage = false
            useExternal = false
        })
        Text("Internal (private; Wine only)", style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = useExternal,
            enabled = gamesRoot != null,
            onClick = {
                // The toggle's own setter clears the stored path, so the
                // path write must come second.
                PrefManager.useExternalStorage = true
                PrefManager.externalStoragePath = gamesRoot!!.absolutePath
                useExternal = true
            },
        )
        Text(
            gamesRoot?.let { "Games folder (${it.absolutePath}; needed for enginehost)" }
                ?: "Games folder (set one up in Settings first)",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { refresh += 1 }) { Text("Refresh") }
        TextButton(onClick = { SteamAccess.logOut() }) { Text("Sign out") }
    }
    OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Search library") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(games.filter { it.name.contains(filter, ignoreCase = true) }, key = { it.appId }) { game ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(game.name, style = MaterialTheme.typography.bodyMedium)
                    val p = progress[game.appId]
                    if (p != null && p < 1f) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
                    }
                }
                when {
                    game.installed -> Text("Installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    progress[game.appId]?.let { it >= 1f } == true -> {
                        Text("Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                    progress[game.appId] != null -> Text("${((progress[game.appId] ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    else -> TextButton(onClick = {
                        if (provisioned == false) {
                            // Offer the environment instead of starting a
                            // download that cannot be played.
                            setupStatus = "Set up the Windows environment first -- the button is above the list."
                            return@TextButton
                        }
                        val started = SteamAccess.startDownload(game.appId) { value -> progress[game.appId] = value }
                        if (started) progress[game.appId] = 0f
                    }) { Text("Download") }
                }
            }
        }
    }
}

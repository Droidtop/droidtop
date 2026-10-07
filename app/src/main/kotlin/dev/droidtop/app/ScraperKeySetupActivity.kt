package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.app.ui.QrCode
import dev.droidtop.library.scraper.ScraperKeyCheck
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.scraper.ScraperKeyState
import dev.droidtop.library.scraper.ScraperPrefs
import dev.droidtop.library.scraper.SteamGridDbPrefs
import dev.droidtop.shell.gamepad.HintTip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The guided setup for the two optional scraper sources that need the
 * person's own credential (docs/SPEC.md 7h, "Keyless by default"): numbered
 * steps of a few words, the official page as a QR code a phone can scan
 * (generated on the device, no network) and an Open button for this
 * device's browser, the input fields, and one test call that ends in
 * "Connected" or a one-line error. Opened from the source's row under
 * Settings > Accounts and sources.
 */
class ScraperKeySetupActivity : AppCompatActivity() {
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
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SetupScreen(service)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SERVICE = "service"

        fun intent(context: Context, service: ScraperKeyService): Intent =
            Intent(context, ScraperKeySetupActivity::class.java).putExtra(EXTRA_SERVICE, service.name)
    }
}

@Composable
private fun SetupScreen(service: ScraperKeyService) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ScraperKeyCheck.state(context, service)) }
    var testing by remember { mutableStateOf(false) }
    // IGDB takes a pair, SteamGridDB one key; the fields buffer here and are stored on Test.
    var clientId by remember { mutableStateOf(ScraperPrefs.clientId(context)) }
    var secret by remember { mutableStateOf(ScraperPrefs.clientSecret(context)) }
    var apiKey by remember { mutableStateOf(SteamGridDbPrefs.apiKey(context)) }
    var result by remember { mutableStateOf<String?>(null) }

    Text(service.title, style = MaterialTheme.typography.headlineMedium)
    HintTip(service.credit) {
        Text(result ?: state, style = MaterialTheme.typography.titleMedium)
    }
    service.steps.forEachIndexed { index, step ->
        Text("${index + 1}  $step", style = MaterialTheme.typography.bodyLarge)
    }
    QrCode(content = service.url, size = 220.dp)
    Button(onClick = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }) { Text("Open") }

    when (service) {
        ScraperKeyService.IGDB -> {
            OutlinedTextField(
                value = clientId, onValueChange = { clientId = it.trim() },
                label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it.trim() },
                label = { Text("Client Secret") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
        }
        ScraperKeyService.STEAMGRIDDB -> OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it.trim() },
            label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !testing, onClick = {
            testing = true
            result = "Testing"
            scope.launch {
                val line = withContext(Dispatchers.IO) {
                    when (service) {
                        ScraperKeyService.IGDB -> ScraperPrefs.set(context, clientId, secret)
                        ScraperKeyService.STEAMGRIDDB -> SteamGridDbPrefs.set(context, apiKey)
                    }
                    ScraperKeyCheck.test(context, service)
                }
                state = ScraperKeyCheck.state(context, service)
                result = line
                testing = false
            }
        }) { Text("Test") }
        if (state != ScraperKeyState.NOT_SET) {
            Button(onClick = {
                when (service) {
                    ScraperKeyService.IGDB -> { ScraperPrefs.set(context, "", ""); clientId = ""; secret = "" }
                    ScraperKeyService.STEAMGRIDDB -> { SteamGridDbPrefs.set(context, ""); apiKey = "" }
                }
                state = ScraperKeyCheck.state(context, service)
                result = null
            }) { Text("Clear") }
        }
    }
}

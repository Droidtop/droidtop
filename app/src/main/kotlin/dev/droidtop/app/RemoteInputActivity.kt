package dev.droidtop.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.app.ui.QrCode
import dev.droidtop.library.credentials.handoff.RemoteInputState
import dev.droidtop.library.credentials.handoff.consoleLine

/**
 * The console side of "Type from another device" (docs/SPEC.md, "Type from another device", Droidtop/tracker#645): a
 * QR code and the plain address while the session waits, the question "Does your device show 482 913?" with Confirm and
 * Refuse, and "Phone connected: typing" once paired. Opening it starts the session when none runs ([RemoteInputs]);
 * leaving it does not end the session, so the person can go to a field and type: Close here, or on the notification,
 * ends it. Opened from the companion's Keys screen, the Quick Menu's row and the key setup screens.
 */
class RemoteInputActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteInputs.start(this)
        setContent {
            DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                Scaffold { padding ->
                    RemoteInputScreen(
                        onLeave = { finish() },
                        onClose = {
                            RemoteInputs.close()
                            finish()
                        },
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, RemoteInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the screen on [displayId] (the companion's screen, for its Keys button) or where the caller is. */
        fun open(context: Context, displayId: Int? = null) {
            val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
            context.startActivity(intent(context), options)
        }
    }
}

@Composable
private fun RemoteInputScreen(onLeave: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val state by RemoteInputs.state.collectAsState()
    val confirm = remember { FocusRequester() }
    // The question is the one thing that needs an answer: the pad's A answers it.
    LaunchedEffect(state is RemoteInputState.Confirming) {
        if (state is RemoteInputState.Confirming) runCatching { confirm.requestFocus() }
    }
    Column(
        modifier = modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Type from another device", style = MaterialTheme.typography.headlineMedium)
        Text(state.consoleLine(), style = MaterialTheme.typography.titleLarge)
        when (val shown = state) {
            is RemoteInputState.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(content = shown.url, size = 220.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shown.url, style = MaterialTheme.typography.bodyLarge)
                    Text("Open it in a browser on the same network. Nothing is typed until you confirm the code here.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is RemoteInputState.Confirming -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { RemoteInputs.confirm() }, modifier = Modifier.focusRequester(confirm)) { Text("Confirm") }
                Button(onClick = { RemoteInputs.refuse() }) { Text("Refuse") }
            }
            is RemoteInputState.Paired -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.lastSent?.let { Text("Last sent: $it", style = MaterialTheme.typography.bodyLarge) }
                shown.notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                shown.clip?.let { Text("Clipboard from the device: $it", style = MaterialTheme.typography.bodyLarge) }
                Text(
                    "Leave this screen and put the cursor in a field. Close ends it, here or on the notification.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state !is RemoteInputState.Closed) Button(onClick = onClose) { Text("Close") }
            TextButton(onClick = onLeave) { Text(if (state is RemoteInputState.Closed) "Done" else "Keep it open and leave") }
        }
    }
}

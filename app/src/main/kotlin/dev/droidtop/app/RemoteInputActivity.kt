package dev.droidtop.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.app.ui.QrCode
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.ui.shared.NoticeCard
import dev.droidtop.library.credentials.handoff.RemoteInputState
import dev.droidtop.library.credentials.handoff.consoleLine

/**
 * The console side of "Type from another device" (docs/SPEC.md, "Type from another device", Droidtop/tracker#645): a
 * QR code and the plain address while the session waits, the question "Does your device show 482 913?" with Confirm and
 * Refuse, and "Phone connected: typing" once paired. Opening it starts the session when none runs ([RemoteInputs]);
 * leaving it does not end the session, so the person can go to a field and type: Close here, or on the notification,
 * ends it. Opened from the companion's Keys screen, the Quick Menu's row and the key setup screens.
 *
 * File transfer (Droidtop/tracker#645, slice 3) has its controls here: the folder files from the phone go into (the
 * system folder picker; Download/droidtop until one is chosen), "Send to the phone" (the system document picker) with
 * "Stop offering", and the lines "Receiving name (size)" and "Phone sent name (size)".
 */
class RemoteInputActivity : AppCompatActivity() {
    private var folder by mutableStateOf("")

    /** Why the last file picked to send was not offered, shown on the screen beside Send to the phone until the next pick. */
    private var offerFailure by mutableStateOf<String?>(null)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            // Kept across restarts, read and write, so the folder is still the one chosen next time.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            ReceiveFolder.choose(this, tree)
            folder = ReceiveFolder.label(this)
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            offerFailure = null
            // The file stays where it is and is read when the phone downloads it, so the permission must outlive this screen.
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            // Reading its name and size is provider work: not on the main thread.
            Thread({
                val offered = RemoteInputs.offer(applicationContext, uri)
                if (!offered) runOnUiThread { offerFailure = "Wait for the QR code, or pick another file" }
            }, "droidtop-remote-offer").apply { isDaemon = true; start() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteInputs.start(this)
        folder = ReceiveFolder.label(this)
        setContent {
            DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                Scaffold { padding ->
                    RemoteInputScreen(
                        folder = folder,
                        offerFailure = offerFailure,
                        onPickFolder = { pickFolder.launch(null) },
                        onDefaultFolder = {
                            ReceiveFolder.choose(this, null)
                            folder = ReceiveFolder.label(this)
                        },
                        onOffer = { pickFile.launch(arrayOf("*/*")) },
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

        /** Opens the screen on [displayId] (the companion's screen, for its Input tab) or where the caller is. */
        fun open(context: Context, displayId: Int? = null) {
            val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
            context.startActivity(intent(context), options)
        }
    }
}

@Composable
private fun RemoteInputScreen(
    folder: String,
    offerFailure: String?,
    onPickFolder: () -> Unit,
    onDefaultFolder: () -> Unit,
    onOffer: () -> Unit,
    onLeave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
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
        Text(stringResource(R.string.remote_input_type_from_another_device), style = MaterialTheme.typography.headlineMedium)
        Text(state.consoleLine(), style = MaterialTheme.typography.titleLarge)
        when (val shown = state) {
            is RemoteInputState.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(content = shown.url, size = 220.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shown.url, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.remote_input_open_it_in_a_browser), style = MaterialTheme.typography.bodyMedium)
                }
            }
            is RemoteInputState.Confirming -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { RemoteInputs.confirm() }, modifier = Modifier.focusRequester(confirm)) { Text(stringResource(R.string.remote_input_confirm)) }
                Button(onClick = { RemoteInputs.refuse() }) { Text(stringResource(R.string.remote_input_refuse)) }
            }
            is RemoteInputState.Paired -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.lastSent?.let { Text(stringResource(R.string.remote_input_last_sent, it), style = MaterialTheme.typography.bodyLarge) }
                // Why the last thing sent did not arrive (a file not stored or not complete, a field that took nothing): a
                // failure that stays on this screen until the next send, drawn as one, not as a line of text among the
                // facts (Droidtop/tracker#645, docs/SPEC.md 7k "Notices").
                shown.notice?.let { NoticeCard(Notice(NoticeTone.Caution, "Not received", body = it.take(Notice.BODY_MAX), details = it), NoticePlacement.Row) }
                shown.clip?.let { Text(stringResource(R.string.remote_input_clipboard_from_the_device, it), style = MaterialTheme.typography.bodyLarge) }
                shown.receiving?.let { Text(stringResource(R.string.remote_input_receiving, it), style = MaterialTheme.typography.bodyLarge) }
                shown.received?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                shown.offered?.let { Text(stringResource(R.string.remote_input_offered_to_the_phone, it), style = MaterialTheme.typography.bodyLarge) }
                Text(
                    "Leave this screen and put the cursor in a field. Close ends it, here or on the notification.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Unit
        }
        if (state !is RemoteInputState.Closed) {
            Text(stringResource(R.string.remote_input_files_from_the_phone_go, folder), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPickFolder) { Text(stringResource(R.string.remote_input_choose_folder)) }
                TextButton(onClick = onDefaultFolder) { Text(stringResource(R.string.remote_input_use_download_droidtop)) }
            }
            // The failure stays on the screen it belongs to (docs/SPEC.md 7k, "Notices"), not a toast.
            offerFailure?.let { NoticeCard(Notice(NoticeTone.Caution, "That file cannot be sent", body = it), NoticePlacement.Row) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOffer) { Text(stringResource(R.string.remote_input_send_to_the_phone)) }
                if ((state as? RemoteInputState.Paired)?.offered != null) TextButton(onClick = { RemoteInputs.withdrawOffer() }) { Text(stringResource(R.string.remote_input_stop_offering)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state !is RemoteInputState.Closed) Button(onClick = onClose) { Text(stringResource(R.string.remote_input_close)) }
            TextButton(onClick = onLeave) { Text(if (state is RemoteInputState.Closed) "Done" else "Keep it open and leave") }
        }
    }
}

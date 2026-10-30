package dev.droidtop.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.content.Context
import dev.droidtop.app.ui.SelectableRow
import dev.droidtop.pluginhost.ElevatedAccessHost
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.DeviceSetup
import dev.droidtop.runtime.tasks.DeviceSetupItem
import dev.droidtop.runtime.tasks.SetupState
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the device-setup step shows: the shell's state and each item's. Read off the main thread. */
internal data class DeviceSetupView(
    val canRun: Boolean,
    val pairing: PairingState,
    val items: List<Pair<DeviceSetupItem, SetupState>>,
)

/** How far wireless debugging is from running a command, for a device with no Shizuku. */
internal enum class PairingState { NOT_OFFERED, TO_PAIR, SWITCHED_OFF, READY }

/** Reads the shell, wireless debugging and every item. Blocking: IO only. */
internal fun readDeviceSetup(context: Context): DeviceSetupView {
    val shell = TaskManager.shell
    val wireless = ElevatedAccessHost.wireless(context)
    val pairing = when {
        !wireless.supported -> PairingState.NOT_OFFERED
        !wireless.paired -> PairingState.TO_PAIR
        wireless.state() != BackendState.READY -> PairingState.SWITCHED_OFF
        else -> PairingState.READY
    }
    val items = DeviceSetup.items.map { it to it.state(context, shell) }.filter { it.second != SetupState.NOT_NEEDED }
    return DeviceSetupView(DeviceSetup.canRun(shell), pairing, items)
}

/**
 * Whether onboarding asks the device-setup step (docs/SPEC.md "Device setup"): something is left to do, and droidtop
 * has, or can get through wireless debugging, a shell to do it with. Blocking: IO only.
 */
internal fun deviceSetupAsks(context: Context): Boolean {
    val view = readDeviceSetup(context)
    return view.items.any { it.second == SetupState.TO_DO } && (view.canRun || view.pairing != PairingState.NOT_OFFERED)
}

/**
 * DEVICE_SETUP (docs/SPEC.md "Device setup", Droidtop/tracker#816). The Android settings droidtop can change itself,
 * through Shizuku when it runs, else through wireless debugging, paired here with a code. Each item is asked first in
 * plain words and can be undone here or in Settings > Accounts and sources > Device setup. Every item is optional.
 */
@Composable
internal fun DeviceSetupStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<DeviceSetupView?>(null) }
    var tick by remember { mutableStateOf(0) }
    var asking by remember { mutableStateOf<Pair<DeviceSetupItem, SetupState>?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    // Read again when the step comes back to the front: the person may have paired, or turned the switch on.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tick) { view = withContext(Dispatchers.IO) { readDeviceSetup(context) } }

    OnboardingScaffold(
        title = "Android settings",
        body = "droidtop can change these for you. Each asks first and can be undone here or in Settings.",
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else "Next", onClick = onContinue),
        focusContentFirst = true,
    ) {
        val current = view ?: return@OnboardingScaffold
        if (!current.canRun) {
            StepSectionLabel("Elevated access")
            when (current.pairing) {
                PairingState.TO_PAIR -> SelectableRow(
                    title = "Pair with wireless debugging",
                    supporting = "Nothing to install. Android shows a code; reply to droidtop's notification with it",
                    onClick = {
                        scope.launch {
                            said = withContext(Dispatchers.IO) { WirelessPairing.start(context) }
                        }
                    },
                )
                PairingState.SWITCHED_OFF -> SelectableRow(
                    title = "Turn on wireless debugging",
                    supporting = "Paired already. Turn it on in Developer options, then come back",
                    onClick = { WirelessPairing.openDeveloperOptions(context) },
                )
                PairingState.NOT_OFFERED, PairingState.READY ->
                    StepNote("Start Shizuku to let droidtop change these. Android 10 and older have no wireless debugging.")
            }
        }
        StepSectionLabel("What droidtop can change")
        for ((item, state) in current.items) {
            val actionable = current.canRun && (state == SetupState.TO_DO || state == SetupState.DONE_BY_DROIDTOP)
            SelectableRow(
                title = stringResource(item.title),
                supporting = stringResource(if (!current.canRun && state == SetupState.TO_DO) DeviceSetup.NO_SHELL else item.line(state)),
                selected = state == SetupState.DONE || state == SetupState.DONE_BY_DROIDTOP,
                onClick = if (actionable) ({ asking = item to state }) else null,
            )
        }
        said?.let { StepNote(it, accent = true) }
    }

    asking?.let { (item, state) ->
        val undoing = state == SetupState.DONE_BY_DROIDTOP
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(item.title)) },
            text = { Text(stringResource(if (undoing) item.undoAsk else item.ask)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    scope.launch {
                        val shell = TaskManager.shell
                        val done = withContext(Dispatchers.IO) { if (undoing) item.undo(context, shell) else item.apply(context, shell) }
                        said = context.getString(
                            when {
                                !done -> dev.droidtop.runtime.R.string.device_setup_failed
                                undoing -> dev.droidtop.runtime.R.string.device_setup_undone
                                else -> dev.droidtop.runtime.R.string.device_setup_done
                            },
                        )
                        tick++
                    }
                }) { Text(stringResource(if (undoing) R.string.device_setup_undo else R.string.device_setup_change)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.device_setup_not_now)) } },
        )
    }
}

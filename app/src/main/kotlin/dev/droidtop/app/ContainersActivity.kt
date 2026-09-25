package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.Mode
import dev.droidtop.runtime.BundledImageRepositories
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ContainerTerminal
import dev.droidtop.runtime.CraneImageCatalogResolver
import dev.droidtop.runtime.ImageCatalogRole
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.runtime.resolveCurrent
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user-facing container/distro manager (docs/SPEC.md §3d — explicit
 * direction: containers are the user's machines, managed first-class, not
 * internal plumbing). Distrobox is the interaction model: a flat list of
 * every container this device has (role, image, live running state) with
 * start/stop/terminal/delete, plus creation from the live-resolved image
 * catalog (§3a "Recommended") or a hand-typed OCI reference ("Custom").
 *
 * Sits directly on [ContainerRuntime] via [ContainerRuntimeFactory] — the
 * same backend the desktop session uses, no separate management path —
 * except for the PRIMARY, whose running state IS the desktop session:
 * starting it opens Desktop (which starts [DesktopSessionService]) and
 * stopping it stops that session, so the plan it boots with is always the
 * current one and nothing runs a compositor the desktop is not showing.
 * A raw `start` of the primary from here used to boot the recorded plan
 * (no CUPS after Printing was switched on) with no host bridge attached,
 * which is a second desktop nobody can see (rig, dq-coordinator-23 F9).
 *
 * One scrolling list holds the whole screen, the create panel included:
 * the panel used to sit above a list of its own and ran off the bottom of
 * a landscape screen, taking "Custom OCI reference" with it (F13).
 */
class ContainersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { dev.droidtop.app.ui.DroidtopTheme { ContainersScreen() } }
    }
}

/** Desktop mode, which starts the desktop session if none runs; where every terminal and container window appears. */
private fun openDesktop(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(BackButtonMenu.EXTRA_MODE, Mode.DESKTOP.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun ContainersScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val session by DesktopSessionService.state.collectAsState()
    var runtime by remember { mutableStateOf<ContainerRuntime?>(null) }
    var containers by remember { mutableStateOf<List<ContainerInfo>?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val rt = runtime ?: ContainerRuntimeFactory.select(context).also { runtime = it }
        containers = runCatching { rt.listContainers() }
            .onFailure { errorMessage = "Couldn't list containers: ${it.message}" }
            .getOrDefault(emptyList())
    }

    // Again whenever the session changes state: the primary's row reads it.
    LaunchedEffect(session::class) { withContext(Dispatchers.IO) { refresh() } }

    fun runAction(label: String, action: suspend (ContainerRuntime) -> Unit) {
        val rt = runtime ?: return
        scope.launch(Dispatchers.IO) {
            busyMessage = label
            errorMessage = null
            runCatching { action(rt) }
                .onFailure { errorMessage = "$label failed: ${it.message}" }
            refresh()
            busyMessage = null
        }
    }

    /**
     * A terminal in [container], as a window on the desktop: the sibling
     * started if its backend needs that, a terminal installed on first use
     * (here, where the person can see it happening), then the terminal run
     * in the desktop session, which outlives this screen, and Desktop
     * brought forward to show it.
     */
    fun openTerminal(info: ContainerInfo) {
        runAction("Getting a terminal ready in ${info.displayName} (the first time installs one)") { rt ->
            val container = info.container
            if (container.role == ContainerRole.SIBLING && rt.siblingsNeedStart && !info.running) rt.start(container)
            ContainerTerminal.ensureInstalled(rt, container)?.let { error(it) }
            val started = DesktopSessionService.runInPrimary { sessionRuntime, _ ->
                ContainerTerminal.failureMessage(ContainerTerminal.open(sessionRuntime, container, info.displayName))
            }
            check(started) { "the desktop stopped. Start it again, then open the terminal." }
            withContext(Dispatchers.Main) { openDesktop(context) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // Content padding, not a padding modifier: the last row scrolls
        // clear of the edge instead of ending under it.
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Containers", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scope.launch(Dispatchers.IO) { refresh() } }) { Text("Refresh") }
                Button(onClick = { showCreate = !showCreate }) { Text(if (showCreate) "Close" else "New container") }
            }
        }
        busyMessage?.let { message ->
            item(key = "busy") { Text(message, color = MaterialTheme.colorScheme.tertiary) }
        }
        errorMessage?.let { message ->
            item(key = "error") { Text(message, color = MaterialTheme.colorScheme.tertiary) }
        }
        if (showCreate) {
            item(key = "create") {
                CreateContainerPanel(
                    enabled = busyMessage == null,
                    onCreateFromRepository = { repo ->
                        runAction("Creating a ${repo.os} container from ${repo.registry}/${repo.repository}") { rt ->
                            rt.createSibling(CraneImageCatalogResolver(context).resolveCurrent(repo).toRootfsImage())
                        }
                        showCreate = false
                    },
                    onCreateFromReference = { reference ->
                        runAction("Creating from $reference") { rt ->
                            rt.createSibling(RootfsImage(reference = reference))
                        }
                        showCreate = false
                    },
                )
            }
        }

        when (val list = containers) {
            null -> item(key = "loading") { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> {
                if (list.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No containers yet. Create one to give this device a real Linux distro.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(list, key = { it.container.id }) { info ->
                    val isPrimary = info.container.role == ContainerRole.PRIMARY
                    ContainerRow(
                        info = info,
                        runtime = runtime,
                        session = if (isPrimary) session else null,
                        desktopConnected = session is DesktopSessionState.Connected,
                        actionsEnabled = busyMessage == null,
                        confirmingDelete = confirmDeleteId == info.container.id,
                        onStart = {
                            if (isPrimary) {
                                openDesktop(context)
                            } else {
                                runAction("Starting ${info.displayName}") { it.start(info.container) }
                            }
                        },
                        onStop = {
                            runAction(if (isPrimary) "Stopping the desktop" else "Stopping ${info.displayName}") { rt ->
                                // The session first, so it does not report
                                // its compositor vanishing as a failure; the
                                // runtime's own stop then ends anything the
                                // session did not start.
                                if (isPrimary) DesktopSessionService.stop(context)
                                rt.stop(info.container)
                            }
                        },
                        onTerminal = { openTerminal(info) },
                        onRename = { name -> runAction("Renaming ${info.displayName}") { it.rename(info.container, name) } },
                        onOpenDesktop = { openDesktop(context) },
                        onDeleteRequested = { confirmDeleteId = info.container.id },
                        onDeleteConfirmed = {
                            confirmDeleteId = null
                            runAction("Deleting ${info.displayName}") { it.destroy(info.container) }
                        },
                        onDeleteCancelled = { confirmDeleteId = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    info: ContainerInfo,
    runtime: ContainerRuntime?,
    /** The desktop session, for the PRIMARY's row only: its state is the primary's state. */
    session: DesktopSessionState?,
    desktopConnected: Boolean,
    actionsEnabled: Boolean,
    confirmingDelete: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTerminal: () -> Unit,
    onRename: (String) -> Unit,
    onOpenDesktop: () -> Unit,
    onDeleteRequested: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteCancelled: () -> Unit,
) {
    val container = info.container
    val isPrimary = container.role == ContainerRole.PRIMARY
    val starting = session is DesktopSessionState.Connecting
    val running = info.running || session is DesktopSessionState.Connected || starting
    // A proot sibling has nothing to start: it is its programs, and each
    // runs on its own (ContainerRuntime.siblingsNeedStart).
    val startable = isPrimary || runtime?.siblingsNeedStart != false
    val state = when {
        starting -> "STARTING"
        running -> "RUNNING"
        startable -> "STOPPED"
        else -> "READY"
    }
    // One rule for every container: a running one is stopped before it
    // can be deleted. Deleting the primary under a live session would tear
    // the desktop down as a side effect of a list row, and a sibling's
    // programs (a terminal, an install) are the same kind of work in
    // progress; the primary used to be guarded and a running sibling not
    // (rig, dq-desk2-01).
    val deleteGuarded = running
    var renaming by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (isPrimary) "primary: the desktop" else "sibling",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info.image?.let { image ->
                    Text(
                        image + (info.digest?.let { " · ${it.removePrefix("sha256:").take(12)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                state,
                style = MaterialTheme.typography.labelMedium,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        (session as? DesktopSessionState.Connecting)?.detail?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> TextButton(onClick = onStop, enabled = actionsEnabled) {
                    Text(if (isPrimary) "Stop the desktop" else "Stop")
                }
                startable -> TextButton(onClick = onStart, enabled = actionsEnabled) {
                    Text(if (isPrimary) "Start the desktop" else "Start")
                }
            }
            // A terminal is a window on the desktop, so it needs one.
            if (desktopConnected) {
                TextButton(onClick = onTerminal, enabled = actionsEnabled) { Text("Terminal") }
            } else if (!isPrimary) {
                TextButton(onClick = onOpenDesktop, enabled = actionsEnabled) { Text("Start the desktop for a terminal") }
            }
            Spacer(Modifier.weight(1f))
            when {
                confirmingDelete -> {
                    Text("Delete container and its storage?", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDeleteCancelled) { Text("Keep") }
                    TextButton(onClick = onDeleteConfirmed, enabled = actionsEnabled) { Text("Delete") }
                }
                deleteGuarded -> Text(
                    if (isPrimary) "Live desktop: stop it to delete" else "Running: stop it to delete",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> TextButton(onClick = onDeleteRequested, enabled = actionsEnabled) { Text("Delete") }
            }
        }
        RenameRow(
            name = info.displayName,
            editing = renaming,
            enabled = actionsEnabled,
            onEdit = { renaming = it },
            onSave = { value ->
                renaming = null
                if (value.trim() != info.displayName) onRename(value)
            },
        )
        dev.droidtop.app.vpn.ContainerVpnRow(container.id, runtime?.hostSocketDir(), enabled = actionsEnabled)
        ContainerDevicesRow(runtime, container, enabled = actionsEnabled)
        if (isPrimary) {
            PrintingRow(
                enabled = actionsEnabled,
                hostSocketDir = runtime?.hostSocketDir(),
                desktopRunning = session is DesktopSessionState.Connected,
            )
        }
    }
}

/**
 * The container's name, which the person chooses (docs/SPEC.md §3d): a
 * default from the image when it is made ("Debian"), changed here. The
 * backend refuses an empty name, one over the length limit, or another
 * container's, and the reason shows in the screen's error line.
 */
@Composable
private fun RenameRow(
    name: String,
    editing: String?,
    enabled: Boolean,
    onEdit: (String?) -> Unit,
    onSave: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
    ) {
        if (editing == null) {
            Column(Modifier.weight(1f)) {
                Text("Name", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onEdit(name) }, enabled = enabled) { Text("Rename") }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = editing,
                    onValueChange = { onEdit(it.take(dev.droidtop.runtime.ContainerNames.MAX_LENGTH)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = { onEdit(null) }) { Text("Cancel") }
            TextButton(onClick = { onSave(editing) }, enabled = enabled && editing.isNotBlank()) { Text("Save") }
        }
    }
}

/**
 * Printing (docs/SPEC.md 4b): CUPS in the primary's provisioning plan,
 * shared with every container through the socket directory. The switch
 * changes the plan, which the next desktop start provisions. Printers are
 * added in CUPS's own web interface, which listens on the device's
 * loopback because a proot container shares the device's network.
 *
 * While the desktop runs the row says whether CUPS is actually up, read
 * from its socket in the shared socket directory: CUPS runs beside the
 * desktop and never holds it up, so a CUPS that did not start is only
 * visible here (and in the desktop log), not as a desktop that hangs.
 */
@Composable
private fun PrintingRow(enabled: Boolean, hostSocketDir: java.io.File?, desktopRunning: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var printing by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { printing = withContext(Dispatchers.IO) { DesktopSetupPrefs.printing(context) } }
    var cupsUp by remember { mutableStateOf(false) }
    LaunchedEffect(desktopRunning, printing, hostSocketDir) {
        while (desktopRunning && printing == true && hostSocketDir != null) {
            cupsUp = withContext(Dispatchers.IO) {
                java.io.File(hostSocketDir, dev.droidtop.runtime.ContainerLayout.CUPS_SOCKET).exists()
            }
            kotlinx.coroutines.delay(2_000)
        }
    }
    val on = printing ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Printing", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                when {
                    on && desktopRunning && cupsUp -> "CUPS is running, for every container."
                    on && desktopRunning -> "CUPS is not running (yet). The desktop does not wait for it; " +
                        "if this stays, why is in droidtop's desktop log and in " +
                        "${dev.droidtop.runtime.ContainerLayout.daemonLog(dev.droidtop.runtime.CompositorProvisioning.PRINTING_DAEMON)} " +
                        "in the desktop's container."
                    on -> "CUPS, for every container. Installed or removed the next time the desktop starts."
                    else -> "Off. Turn on to install CUPS the next time the desktop starts."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (on && desktopRunning && cupsUp) {
                TextButton(onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("http://127.0.0.1:${dev.droidtop.runtime.CompositorProvisioning.PRINTING_WEB_PORT}/admin"),
                        ),
                    )
                }) { Text("Add a printer") }
            }
        }
        androidx.compose.material3.Switch(
            checked = on,
            enabled = enabled,
            onCheckedChange = { value ->
                printing = value
                scope.launch(Dispatchers.IO) { DesktopSetupPrefs.setPrinting(context, value) }
            },
        )
    }
}

@Composable
private fun CreateContainerPanel(
    enabled: Boolean,
    onCreateFromRepository: (KnownImageRepository) -> Unit,
    onCreateFromReference: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Same live-catalog model as onboarding's desktop step (docs/SPEC.md
    // §3a): the seed list only names repositories; the current tag
    // (ImageTags.current) resolves at create time. arm64 is the hard
    // filter -- droidtop only targets ARM64 hardware, a repo without it
    // can't run here at all.
    val candidates = remember {
        BundledImageRepositories.load(context).repositories
            .filter { it.arm64Available }
            .filter { it.role == ImageCatalogRole.SIBLING || it.role == ImageCatalogRole.BOTH }
    }
    var customReference by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text("Recommended", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Each is created from the distribution's current release.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidates.forEach { repo ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(repo.os, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${repo.registry}/${repo.repository}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onCreateFromRepository(repo) }, enabled = enabled) { Text("Create") }
            }
        }
        Text(
            "Custom OCI reference",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (customReference.isEmpty()) {
                    Text("docker.io/library/debian:bookworm", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = customReference,
                    onValueChange = { customReference = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onCreateFromReference(customReference.trim()) },
                enabled = enabled && customReference.isNotBlank(),
            ) { Text("Create") }
        }
    }
}

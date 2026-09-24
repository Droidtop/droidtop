package dev.droidtop.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.app.ui.SelectableRow
import dev.droidtop.library.settings.Mode
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.ContainerOpenWith
import dev.droidtop.runtime.ContainerPackageManager
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.OpenWithKind
import dev.droidtop.runtime.SharedVolume
import dev.droidtop.runtime.windows.WinePrefixes
import dev.droidtop.shell.gamepad.Measure
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Open with droidtop" (docs/SPEC.md 4b): a downloaded `.exe`, `.msi`,
 * `.deb`, `.rpm` or `.AppImage`, tapped in a browser or file manager,
 * opens here, and runs or installs from where it is. Nothing is copied.
 *
 * Windows programs go to a Wine prefix through the same engine a library
 * game uses ([WinePrefixes]); packages and AppImages go to a container
 * through the desktop session ([DesktopSessionService.runInPrimary]), so
 * the program lives as long as the session, not as long as this screen.
 * The choice is the one choice component; the last one per extension is
 * offered first, and "always" is an explicit row, never a silent default.
 *
 * Enabled as a component only while Desktop mode is on
 * ([dev.droidtop.library.settings.ModePiece.DESKTOP_OPEN_WITH]).
 */
class OpenWithActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = incomingUri(intent)
        setContent { dev.droidtop.app.ui.DroidtopTheme { OpenWithScreen(uri, onClose = ::finish) } }
    }

    private fun incomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> intent.data
    }

    companion object {
        /** The last install started from here, so a screen still open can show how it ended. */
        internal val lastInstall = MutableStateFlow<InstallOutcome?>(null)
    }
}

internal data class InstallOutcome(val fileName: String, val finished: Boolean, val failure: String?)

/** One thing the file can be opened with. [key] is what [OpenWithPrefs] remembers. */
private data class OpenWithChoice(
    val key: String,
    val title: String,
    val supporting: String,
    val available: Boolean,
    val open: suspend (Context) -> String?,
)

private sealed interface OpenWithState {
    data object Resolving : OpenWithState
    data class Unreadable(val message: String) : OpenWithState
    data class Ready(val file: File, val kind: OpenWithKind, val choices: List<OpenWithChoice>, val note: String?) : OpenWithState
}

@Composable
private fun OpenWithScreen(uri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by DesktopSessionService.state.collectAsState()
    val connected = session is DesktopSessionState.Connected
    var state by remember { mutableStateOf<OpenWithState>(OpenWithState.Resolving) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var always by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val install by OpenWithActivity.lastInstall.collectAsState()

    var autoOpened by remember { mutableStateOf(false) }

    // Re-resolved when the desktop connects: container choices need it.
    LaunchedEffect(uri, connected) {
        val (resolved, remembered, rememberedAlways) = withContext(Dispatchers.IO) {
            val resolved = resolveState(context, uri, connected)
            val kind = (resolved as? OpenWithState.Ready)?.kind
            Triple(resolved, kind?.let { OpenWithPrefs.last(context, it) }, kind?.let { OpenWithPrefs.always(context, it) } == true)
        }
        state = resolved
        val ready = resolved as? OpenWithState.Ready ?: return@LaunchedEffect
        val preferred = ready.choices.firstOrNull { it.key == remembered && it.available }
            ?: ready.choices.firstOrNull { it.available }
        if (ready.choices.none { it.key == selectedKey && it.available }) selectedKey = preferred?.key
        if (!autoOpened) always = rememberedAlways
        // "Always" was chosen for this kind: open straight away, once, and
        // keep this screen up with the way back to choosing.
        if (!autoOpened && rememberedAlways && preferred != null && preferred.key == remembered) {
            autoOpened = true
            working = "Opening ${ready.file.name} with ${preferred.title}…"
            failure = preferred.open(context)
            working = null
            if (failure == null && !ready.kind.isInstall()) onClose()
        }
    }

    fun open(ready: OpenWithState.Ready) {
        val choice = ready.choices.firstOrNull { it.key == selectedKey && it.available } ?: return
        OpenWithPrefs.remember(context, ready.kind, choice.key, always)
        scope.launch {
            working = "Opening ${ready.file.name} with ${choice.title}…"
            failure = choice.open(context)
            working = null
            if (failure == null && !ready.kind.isInstall()) onClose()
        }
    }

    val window = currentShellWindow()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = window.edgePadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = Space.Xl, bottom = Space.Lg),
            verticalArrangement = Arrangement.spacedBy(Space.Md),
        ) {
            when (val s = state) {
                OpenWithState.Resolving -> Title("Opening…", null)
                is OpenWithState.Unreadable -> Title("Can't open this file", s.message)
                is OpenWithState.Ready -> {
                    Title(
                        "Open ${s.file.name}",
                        if (s.kind.runsInWine) {
                            "Runs in a Windows environment, from where it is."
                        } else if (s.kind.isInstall()) {
                            "Installs into a container, from where it is."
                        } else {
                            "Runs in a container, on the desktop, from where it is."
                        },
                    )
                    s.choices.forEach { choice ->
                        SelectableRow(
                            title = choice.title,
                            supporting = choice.supporting,
                            selected = choice.available && choice.key == selectedKey,
                            onClick = if (choice.available && working == null) ({ selectedKey = choice.key }) else null,
                        )
                    }
                    s.note?.let { Note(it) }
                    if (s.choices.any { it.available }) {
                        SelectableRow(
                            title = "Always open .${s.kind.extension} files this way",
                            supporting = if (always) "On: next time this opens without asking" else "Off: asks every time",
                            selected = always,
                            onClick = if (working == null) ({ always = !always }) else null,
                        )
                    }
                    working?.let { Note(it, accent = true) }
                    failure?.let { Note(it, accent = true) }
                    install?.takeIf { it.fileName == s.file.name }?.let { outcome ->
                        Note(
                            when {
                                !outcome.finished -> "Installing ${outcome.fileName}…"
                                outcome.failure == null -> "${outcome.fileName} is installed."
                                else -> outcome.failure
                            },
                            accent = true,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.Xl),
            horizontalArrangement = Arrangement.spacedBy(Space.Md, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = window.minTouchTarget)) {
                Text("Close", style = TypeRole.button)
            }
            val ready = state as? OpenWithState.Ready
            if (ready != null && !ready.kind.runsInWine && !connected) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .putExtra(BackButtonMenu.EXTRA_MODE, Mode.DESKTOP.id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.heightIn(min = window.minTouchTarget),
                ) { Text("Start the desktop", style = TypeRole.button) }
            }
            if (ready != null && ready.choices.any { it.available }) {
                Button(
                    onClick = { open(ready) },
                    enabled = working == null && selectedKey != null,
                    modifier = Modifier
                        .heightIn(min = window.minTouchTarget)
                        .then(if (window.portrait) Modifier.weight(1f) else Modifier),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MenuTokens.Accent,
                        contentColor = MenuTokens.OverlaySurface,
                    ),
                ) { Text(if (ready.kind.isInstall()) "Install" else "Open", style = TypeRole.button) }
            }
        }
    }
}

@Composable
private fun Title(title: String, body: String?) {
    Text(title, color = MenuTokens.OnSurface, style = TypeRole.screenTitle)
    body?.let {
        Text(it, color = MenuTokens.OnSurfaceMuted, style = TypeRole.body, modifier = Modifier.widthIn(max = Measure.bodyMaxWidth))
    }
}

@Composable
private fun Note(text: String, accent: Boolean = false) {
    Text(
        text,
        color = if (accent) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
        style = TypeRole.supporting,
        modifier = Modifier.widthIn(max = Measure.bodyMaxWidth),
    )
}

private fun OpenWithKind.isInstall() = this == OpenWithKind.DEB || this == OpenWithKind.RPM

private suspend fun resolveState(context: Context, uri: Uri?, desktopConnected: Boolean): OpenWithState {
    val file = uri?.let { OpenWithSource.resolve(context, it) }
    val name = file?.name ?: uri?.let { OpenWithSource.displayName(context, it) }
    val kind = name?.let { OpenWithKind.of(it) }
        ?: return OpenWithState.Unreadable("droidtop opens .exe, .msi, .deb, .rpm and .AppImage files.")
    if (file == null || !file.canRead()) {
        return OpenWithState.Unreadable(
            "droidtop runs a file from where it is, and this one isn't in a folder it can read. " +
                "Save it to your Download folder first, then open it from there.",
        )
    }
    return if (kind.runsInWine) wineChoices(context, file, kind) else containerChoices(context, file, kind, desktopConnected)
}

private fun wineChoices(context: Context, file: File, kind: OpenWithKind): OpenWithState {
    val prefixes = WinePrefixes.list(context)
    val choices = prefixes.mapIndexed { index, prefix ->
        OpenWithChoice(
            key = "wine:${prefix.id}",
            title = prefix.name,
            supporting = if (index == 0) "droidtop's Windows environment" else "A game's own Windows environment",
            available = true,
            open = { ctx ->
                val result = WinePrefixes.open(ctx, prefix.id, file)
                if (result.succeeded) null else "Couldn't start it: ${result.detail}"
            },
        )
    }
    val note = if (prefixes.isEmpty()) {
        "There is no Windows environment yet. Run \"Set up Windows games\" in Settings, then open the file again."
    } else {
        null
    }
    return OpenWithState.Ready(file, kind, choices, note)
}

private suspend fun containerChoices(context: Context, file: File, kind: OpenWithKind, desktopConnected: Boolean): OpenWithState {
    if (!desktopConnected) {
        return OpenWithState.Ready(file, kind, emptyList(), "Containers run with the desktop, and it isn't running. Start it, and this list fills in.")
    }
    val containerPath = ContainerLayout.sharedStorageToContainerPath(SharedVolume.mounted(context), file)
        ?: return OpenWithState.Unreadable(
            "Containers see your device's shared storage, and this file isn't on it. " +
                "Save it to your Download folder first, then open it from there.",
        )
    val runtime = ContainerRuntimeFactory.select(context)
    val containers = runCatching { runtime.listContainers() }.getOrDefault(emptyList())
        .sortedBy { if (it.container.role == ContainerRole.PRIMARY) 0 else 1 }
    val choices = containers.map { info ->
        val container = info.container
        // proot runs each exec as its own session; droidspaces needs the
        // container up.
        val reachable = runtime.backend == ContainerBackend.PROOT || info.running
        val manager: ContainerPackageManager? = if (reachable && kind.isInstall()) {
            runCatching { ContainerOpenWith.packageManagerFrom(runtime.exec(container, ContainerOpenWith.PROBE_COMMAND)) }.getOrNull()
        } else {
            null
        }
        val accepts = reachable && ContainerOpenWith.accepts(kind, manager)
        val role = if (container.role == ContainerRole.PRIMARY) "The desktop's container" else "Container"
        OpenWithChoice(
            key = "container:${container.id}",
            title = container.id,
            supporting = when {
                !reachable -> "$role · stopped: start it in Containers"
                !accepts -> "$role · has no package manager for .${kind.extension} files"
                manager != null -> "$role · ${manager.binary}"
                else -> role
            },
            available = accepts,
            open = { _ -> runInContainer(container.id, file.name, kind, manager, containerPath) },
        )
    }
    val note = if (containers.isEmpty()) "There are no containers yet. Create one in Containers." else null
    return OpenWithState.Ready(file, kind, choices, note)
}

/**
 * Hands the command to the desktop session, which outlives this screen.
 * An install reports how it ended through [OpenWithActivity.lastInstall];
 * any failure also lands on the desktop's own launch-failure banner.
 */
private fun runInContainer(
    containerId: String,
    fileName: String,
    kind: OpenWithKind,
    manager: ContainerPackageManager?,
    containerPath: String,
): String? {
    val install = kind.isInstall()
    if (install) OpenWithActivity.lastInstall.value = InstallOutcome(fileName, finished = false, failure = null)
    val started = DesktopSessionService.runInPrimary { runtime, primary ->
        val target = if (primary.id == containerId) {
            primary
        } else {
            runtime.listContainers().firstOrNull { it.container.id == containerId }?.container
        }
        val failure = if (target == null) {
            "The container $containerId no longer exists."
        } else {
            val result = runtime.exec(target, ContainerOpenWith.command(kind, manager, containerPath), ContainerOpenWith.environment(kind))
            if (result.succeeded) {
                null
            } else {
                val detail = result.stderr.ifBlank { result.stdout }.trim().lines().takeLast(6).joinToString("\n")
                "$fileName ${if (install) "didn't install" else "exited"} (code ${result.exitCode})" +
                    if (detail.isEmpty()) "." else ":\n$detail"
            }
        }
        if (install) OpenWithActivity.lastInstall.value = InstallOutcome(fileName, finished = true, failure = failure)
        failure
    }
    if (!started) {
        if (install) OpenWithActivity.lastInstall.value = null
        return "The desktop stopped. Start it again, then open the file."
    }
    return null
}

/**
 * The file behind a `VIEW` or `SEND` URI, as a real path on shared
 * storage, or null when only a content provider serves it. Covers what
 * browsers and file managers actually hand over: a `file://` path, the
 * system file picker's external-storage documents, the Downloads
 * provider's `raw:` ids, and any provider that still answers the media
 * `_data` column (readable to an app with all-files access).
 */
internal object OpenWithSource {
    fun resolve(context: Context, uri: Uri): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let(::File)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        documentPath(context, uri)?.let { return it }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let(::File) else null
            }
        }.getOrNull()?.takeIf { it.isFile }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun documentPath(context: Context, uri: Uri): File? {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val id = DocumentsContract.getDocumentId(uri)
        if (id.startsWith("raw:")) return File(id.removePrefix("raw:"))
        if (uri.authority != "com.android.externalstorage.documents") return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }
        return File(root, relative)
    }
}

/** The last choice per extension, and whether it is "always". */
internal object OpenWithPrefs {
    private const val FILE = "open_with"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun last(context: Context, kind: OpenWithKind): String? = prefs(context).getString("${kind.name}.last", null)

    fun always(context: Context, kind: OpenWithKind): Boolean = prefs(context).getBoolean("${kind.name}.always", false)

    fun remember(context: Context, kind: OpenWithKind, key: String, always: Boolean) {
        prefs(context).edit().putString("${kind.name}.last", key).putBoolean("${kind.name}.always", always).apply()
    }
}

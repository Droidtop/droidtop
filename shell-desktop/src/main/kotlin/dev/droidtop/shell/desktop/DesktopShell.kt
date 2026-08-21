package dev.droidtop.shell.desktop

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.runtime.DisplayOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Desktop style" shell: a taskbar + start menu wrapped around the primary
 * container's compositor output, which :host-bridge presents onto a real
 * Android [Surface] (see [HostBridge.presentOutput]).
 *
 * [hostBridge] and [primaryOutput] are nullable on purpose: as of this
 * writing, dev.droidtop.app.DesktopSessionService — the thing that's
 * actually supposed to create the primary container and connect a
 * [HostBridge] to its Wayland socket — is still a TODO stub (see its own
 * source). Passing null here (which :app does today) renders this shell's
 * real chrome — taskbar, start menu, launching library entries all work
 * right now — with an honest "no desktop session" placeholder standing in
 * for the live output, rather than faking a connection that doesn't exist.
 * Once DesktopSessionService is real, wiring a live HostBridge/DisplayOutput
 * through to this composable is the only change needed here.
 */
@Composable
fun DesktopShell(
    library: Library,
    hostBridge: HostBridge?,
    primaryOutput: DisplayOutput?,
) {
    var startMenuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220))) {
        DesktopViewport(hostBridge, primaryOutput)

        Taskbar(
            startMenuOpen = startMenuOpen,
            onToggleStartMenu = { startMenuOpen = !startMenuOpen },
        )

        if (startMenuOpen) {
            StartMenu(
                library = library,
                onDismiss = { startMenuOpen = false },
            )
        }
    }
}

@Composable
private fun BoxScope.DesktopViewport(hostBridge: HostBridge?, primaryOutput: DisplayOutput?) {
    if (hostBridge != null && primaryOutput != null) {
        var presentFailed by remember { mutableStateOf(false) }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            presentFailed = !hostBridge.presentOutput(primaryOutput, holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            hostBridge.stopPresenting()
                        }
                    })
                }
            },
        )

        if (presentFailed) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't present the desktop output.", color = Color.White)
                Text(
                    "Check that the primary container's compositor is running.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Desktop session not started", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(
                "The primary container (Wine/Linux desktop) isn't running yet — " +
                    "start it to see your desktop here.",
                color = Color.Gray,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.Taskbar(startMenuOpen: Boolean, onToggleStartMenu: () -> Unit) {
    var clockText by remember { mutableStateOf(formatClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = formatClock()
            delay(30_000)
        }
    }

    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF11182B)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onToggleStartMenu, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (startMenuOpen) "Close" else "Start")
        }
        Spacer(modifier = Modifier.width(1.dp).height(32.dp).background(Color(0xFF2A3454)))
        Spacer(modifier = Modifier.weight(1f))
        Text(clockText, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

private fun formatClock(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

@Composable
private fun BoxScope.StartMenu(library: Library, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(library) {
        entries = library.scanAll()
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(bottom = 48.dp)
            .width(320.dp)
            .background(Color(0xFF161F38)),
    ) {
        val currentEntries = entries
        when {
            currentEntries == null -> Text("Loading…", color = Color.White, modifier = Modifier.padding(16.dp))
            currentEntries.isEmpty() -> Text(
                "Nothing in the library yet.",
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )

            else -> LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(currentEntries, key = { it.id }) { entry ->
                    Text(
                        entry.title,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .clickable {
                                scope.launch { library.launch(entry) }
                                onDismiss()
                            },
                    )
                }
            }
        }
    }
}

package dev.droidtop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.hostbridge.Toplevel
import kotlinx.coroutines.launch

/**
 * The Linux desktop's own windows on the companion's Apps tab while a desktop session runs (docs/SPEC.md "The
 * companion's tabs", Apps; Droidtop/tracker#425): each window with Raise or Restore, Minimize and Close, the same
 * actions as Desktop's taskbar ([dev.droidtop.hostbridge.DesktopWindows], the bridge's close request), so the two
 * never disagree. Nothing is drawn without a session.
 */
@Composable
internal fun CompanionDesktopWindows() {
    val state by DesktopSessionService.state.collectAsState()
    val session = state as? DesktopSessionState.Connected ?: return
    val bridge = session.hostBridge
    var toplevels by remember(bridge) { mutableStateOf(bridge.toplevels()) }
    LaunchedEffect(bridge) {
        bridge.toplevelChanges.collect {
            toplevels = bridge.toplevels()
            session.windows.forget(toplevels)
        }
    }
    val hidden by session.windows.hidden.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    CompanionCard("Linux desktop") {
        if (toplevels.isEmpty()) {
            CompanionNote("No windows open")
            return@CompanionCard
        }
        toplevels.forEach { toplevel ->
            val minimized = session.windows.isMinimized(toplevel, hidden)
            WindowRow(
                toplevel = toplevel,
                minimized = minimized,
                onRaise = { scope.launch { session.windows.raise(toplevel) } },
                onMinimize = {
                    scope.launch {
                        if (!session.windows.minimize(toplevel)) {
                            dev.droidtop.library.settings.Notices.flash(
                                "Couldn't minimize this window",
                                tone = dev.droidtop.library.settings.NoticeTone.Caution,
                                context = context,
                            )
                        }
                    }
                },
                onClose = { bridge.closeToplevel(toplevel.id) },
            )
        }
    }
}

@Composable
private fun WindowRow(toplevel: Toplevel, minimized: Boolean, onRaise: () -> Unit, onMinimize: () -> Unit, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            toplevel.title.ifBlank { toplevel.appId },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompanionNote(windowState(toplevel, minimized))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (minimized || !toplevel.activated) CompanionPill(if (minimized) "Restore" else "Raise", onClick = onRaise)
            if (!minimized) CompanionPill("Minimize", onClick = onMinimize)
            CompanionPill("Close", onClick = onClose)
        }
    }
}

/** The window's one-line state under its title: in front, minimized, or open behind. Pure. */
internal fun windowState(toplevel: Toplevel, minimized: Boolean): String {
    val what = toplevel.appId.takeIf { it.isNotBlank() && it != toplevel.title }
    val where = when {
        minimized -> "Minimized"
        toplevel.activated -> "In front"
        else -> "Open"
    }
    return listOfNotNull(where, what).joinToString(" · ")
}

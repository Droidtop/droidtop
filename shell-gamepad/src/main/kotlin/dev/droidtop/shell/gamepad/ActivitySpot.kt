package dev.droidtop.shell.gamepad

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.library.integrations.HappeningNow
import dev.droidtop.library.settings.JobLook
import dev.droidtop.runtime.systemstatus.NotificationRows
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.ui.shared.JobRing

/**
 * The status area's one contextual spot (docs/SPEC.md 7i "Home", Droidtop/tracker#742; the owner: "Have a contextual
 * spot that shows an icon for whatever's currently happening"): the icon of what is happening now ([HappeningNow]),
 * inside a ring that fills as it goes or turns while it does not know, and a small count when more than one thing
 * runs. Nothing is drawn while nothing runs. It lives on the status pill, so Home's band and the pill floating over
 * every other page carry the same spot. A tap opens where the jobs are ([onOpen], the Quick Menu's Downloads tab);
 * touch only, never a stop of the pad, as nothing in the top bar is.
 */
@Composable
fun ActivitySpot(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val jobs by remember { PluginJobsCenter.entries() }.collectAsState()
    // One job on the spot while it runs, its fill only moving forward (Droidtop/tracker#834).
    val held = remember { HappeningNow.Held() }
    val now = remember(jobs) { held.next(jobs) }
    val colors = shellJobColors()
    if (now == null) {
        // With no job of droidtop's own, an app's status that has progress (a sync, a download) takes the spot with
        // the app's icon and a real ring (Droidtop/tracker#786 S13a: "Status goes to the status line's activity spot
        // with the app's icon and a real bar when it has progress"), from the one notification model.
        val notifications by NotificationsStore.items.collectAsState()
        val status = remember(notifications) { NotificationRows.status(notifications).firstOrNull { it.progress != null } } ?: return
        val fraction = status.progress ?: return
        val icon = remember(status.icon) { status.icon?.asImageBitmap() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(32.dp)
                .semantics { contentDescription = "${status.appLabel}: ${status.title}" }
                .padRow(onClick = onOpen),
        ) {
            JobRing(fraction = fraction, look = if (fraction < 0f) JobLook.CHECKING else JobLook.ACTIVE, colors = colors, size = 28.dp)
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(16.dp).clip(CircleShape))
            } else {
                Icon(Icons.Outlined.HourglassTop, contentDescription = null, tint = MenuTokens.OnSurface, modifier = Modifier.size(16.dp))
            }
        }
        return
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .semantics { contentDescription = now.words }
            .padRow(onClick = onOpen),
    ) {
        JobRing(fraction = now.fraction, look = now.look, colors = colors, size = 28.dp)
        Icon(iconOf(now.kind), contentDescription = null, tint = MenuTokens.OnSurface, modifier = Modifier.size(16.dp))
        if (now.running > 1) {
            Text(
                now.running.coerceAtMost(9).toString(),
                color = MenuTokens.OnSelected,
                style = TypeRole.supporting,
                modifier = Modifier.align(Alignment.TopEnd).background(MenuTokens.Selected, CircleShape).padding(horizontal = 3.dp),
            )
        }
    }
}

/** The glyph for each kind of thing that can be happening. */
private fun iconOf(kind: HappeningNow.Kind): ImageVector = when (kind) {
    HappeningNow.Kind.DOWNLOAD -> Icons.Outlined.Download
    HappeningNow.Kind.SYNC -> Icons.Outlined.CloudSync
    HappeningNow.Kind.SCAN -> Icons.Outlined.FindInPage
    HappeningNow.Kind.SCRAPE -> Icons.Outlined.Image
    HappeningNow.Kind.UPDATE_CHECK -> Icons.Outlined.SystemUpdate
    HappeningNow.Kind.PLUGIN -> Icons.Outlined.Extension
    HappeningNow.Kind.OTHER -> Icons.Outlined.HourglassTop
}

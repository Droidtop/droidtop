package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.Locked

/**
 * A control droidtop may not change yet, kept in place (docs/SPEC.md "The companion's tabs", Droidtop/tracker#786 S2a,
 * rule 3): its name, its state in the read-only look (muted, with a meter when [fraction] is given, never a live
 * track), and "Not allowed yet" where a control would be. A tap opens the one sheet in place under it
 * ([CompanionLockedSheet]). TalkBack hears one node: the name, the state, and that it is read-only.
 */
@Composable
internal fun CompanionReadOnlyRow(title: String, value: String, locked: Locked, fraction: Float? = null) {
    var open by remember(title) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    CompanionTile(onClick = { open = !open }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clearAndSetSemantics {
                    contentDescription = title
                    stateDescription = "$value, read-only"
                    role = Role.Button
                    fraction?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) }
                    onClick { open = !open; true }
                },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            if (fraction != null) {
                // The state, drawn; muted and without a thumb, so it never reads as something to drag.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .drawBehind {
                            val radius = CornerRadius(size.height / 2)
                            drawRoundRect(colors.surfaceVariant, Offset.Zero, size, radius)
                            drawRoundRect(colors.onSurfaceVariant, Offset.Zero, Size(size.width * fraction.coerceIn(0f, 1f), size.height), radius)
                        },
                )
            }
            Text(stringResource(R.string.companion_not_allowed_yet), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    if (open) CompanionLockedSheet(locked, onClose = { open = false })
}

/**
 * The one sheet for every control droidtop may not change yet (rule 3): what droidtop would be allowed to change, one
 * button for the next step, and Android's own page as a second button where there is one. Drawn in place, as the
 * companion's question is ([CompanionAsk]), so it never takes the focus off the other screen.
 */
@Composable
internal fun CompanionLockedSheet(locked: Locked, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(locked.allows, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CompanionPill(locked.step.label, selected = true) {
                onClose()
                locked.step.run(context)
            }
            locked.android?.let { page ->
                CompanionPill(page.label) {
                    onClose()
                    page.run(context)
                }
            }
            CompanionPill("Not now") { onClose() }
        }
    }
}

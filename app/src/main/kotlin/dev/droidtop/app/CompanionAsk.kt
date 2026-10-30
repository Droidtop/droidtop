package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The companion's one question (docs/SPEC.md "The companion's tabs", Droidtop/tracker#786 S2a, rule 8: "through the
 * one dialog with the safe choice first"): the question naming what it acts on, then the safe answer first and large,
 * the action well apart from it. Drawn in place where it was asked, not as a window: the companion is touch-only
 * (`FLAG_NOT_FOCUSABLE`), and a dialog window of its own would take the focus, and with it the pad, off the game on
 * the other screen. Stopping a game, an app or a plugin's row, a page's button and RetroArch's setup all ask through
 * this; there is no other.
 */
@Composable
internal fun CompanionAsk(
    question: String,
    safe: String,
    action: String,
    onSafe: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(question, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            AskButton(safe, primary = true, modifier = Modifier.weight(1f), onClick = onSafe)
            AskButton(action, primary = false, modifier = Modifier.widthIn(min = 120.dp), onClick = onAction)
        }
    }
}

@Composable
private fun AskButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompanionTile(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(if (primary) colors.primary else colors.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (primary) colors.onPrimary else colors.onSurface)
        }
    }
}

package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.ScreenShapeRevert
import kotlinx.coroutines.delay

/**
 * The companion's Keep bar (Droidtop/tracker#786 S14): the screen-shape change just made, the seconds before it goes
 * back, Keep and Undo. Drawn only while a change waits; a screen reader hears it once, politely.
 */
@Composable
internal fun CompanionKeepBar() {
    val pending by ScreenShapeRevert.pending.collectAsState()
    val change = pending ?: return
    var now by remember(change) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(change) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val left = ScreenShapeRevert.secondsLeft(change.deadlineMillis, now)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.secondaryContainer)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(change.label, style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer, modifier = Modifier.weight(1f))
        CompanionPill("Keep ($left)", selected = true) { ScreenShapeRevert.keep() }
        CompanionPill("Undo") { ScreenShapeRevert.revertNow() }
    }
}

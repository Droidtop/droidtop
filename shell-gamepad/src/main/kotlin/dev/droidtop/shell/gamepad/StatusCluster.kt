package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The status cluster as Gaming draws it: floating at the top-right over the
 * page on a soft plate rather than in a header band (docs/SPEC.md 7j); a
 * readout and the tap route to the Quick Menu. Reports its footprint to
 * [StatusClusterRoom].
 */
@Composable
internal fun FloatingStatusCluster(onClick: () -> Unit, onActivity: () -> Unit, modifier: Modifier = Modifier) {
    val window = LocalShellWindow.current
    val density = LocalDensity.current
    DisposableEffect(Unit) { onDispose { StatusClusterRoom.size = DpSize.Zero } }
    StatusPill(
        onClick = onClick,
        onActivity = onActivity,
        modifier = modifier
            .onSizeChanged { px -> StatusClusterRoom.size = with(density) { DpSize(px.width.toDp(), px.height.toDp()) } }
            .padding(horizontal = window.barPadding, vertical = STATUS_INSET),
    )
}

/** How far the status pill sits from the window's top edge, floating or in Home's band (Droidtop/tracker#675). */
internal val STATUS_INSET = 8.dp

/**
 * The status cluster on its pill: the one look of the facts wherever they float, the page's corner and Home's band
 * alike (Droidtop/tracker#675). [plate] is the pill's fill: the floating plate over a page, the surface in Home's band.
 * The activity spot ([ActivitySpot]) leads it while something runs, so every page has it (Droidtop/tracker#742);
 * [onActivity] is its tap.
 */
@Composable
internal fun StatusPill(onClick: () -> Unit, onActivity: () -> Unit, modifier: Modifier = Modifier, plate: Color = MenuTokens.FloatingPlate) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(plate, Corners.Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        ActivitySpot(onOpen = onActivity)
        StatusCluster(showBatteryPercent = true, onClick = onClick)
    }
}

package dev.droidtop.shell.gamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.systemstatus.NetworkKind
import dev.droidtop.runtime.systemstatus.SystemStatus
import dev.droidtop.runtime.systemstatus.SystemStatusSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * How much of the window's top-right corner the floating status cluster
 * covers, margins included, measured as it is drawn; zero while it is not
 * drawn. A row along the top edge (the view strip, [ViewStrip]) ends short
 * of it and shelves with no strip start under it, so nothing of the page
 * sits beneath the clock and battery (Droidtop/tracker#292). One cluster
 * per shell, so one value.
 */
internal object StatusClusterRoom {
    var size by mutableStateOf(DpSize.Zero)
}

/**
 * The status cluster as Gaming draws it: floating at the top-right over the
 * page on a soft plate rather than in a header band (docs/SPEC.md 7j); a
 * readout and the tap route to the Quick Menu. Reports its footprint to
 * [StatusClusterRoom].
 */
@Composable
internal fun FloatingStatusCluster(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val window = LocalShellWindow.current
    val density = LocalDensity.current
    DisposableEffect(Unit) { onDispose { StatusClusterRoom.size = DpSize.Zero } }
    Box(
        modifier = modifier
            .onSizeChanged { px -> StatusClusterRoom.size = with(density) { DpSize(px.width.toDp(), px.height.toDp()) } }
            .padding(horizontal = window.edgePadding, vertical = 8.dp)
            .background(MenuTokens.Surface.copy(alpha = 0.58f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        StatusCluster(showBatteryPercent = true, onClick = onClick)
    }
}

/**
 * The Gaming header's status readout: clock, connectivity and battery
 * (owner, 2026-09-29: nothing outside the Quick Menu showed them).
 *
 * Deliberately quiet so the top bar does not become an Android status bar:
 * no plate, no row of notification icons, one muted colour, drawn at the
 * tab labels' weight or lighter, and only the three facts a handheld player
 * looks for. The colour changes only when something needs attention (low
 * battery, charging, no connection). Everything is read off the main
 * thread (`SystemStatus.flow` on the IO dispatcher, the clock's format
 * lookup likewise) and is one shared source with the Quick Menu and the
 * companion (`SystemStatus`), never a receiver of the shell's own.
 *
 * Tapping it opens the Quick Menu, which has the controls behind these
 * readings. Not a focus target, like everything in the top bar.
 */
@Composable
internal fun StatusCluster(showBatteryPercent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by produceState<SystemStatusSnapshot?>(null, context) {
        SystemStatus.flow(context).flowOn(Dispatchers.IO).collect { value = it }
    }
    val clock by produceState("", context) {
        while (true) {
            val now = System.currentTimeMillis()
            value = withContext(Dispatchers.IO) {
                android.text.format.DateFormat.getTimeFormat(context).format(Date(now))
            }
            // Wake on the next minute boundary, not on a fixed poll.
            delay(60_000L - (now % 60_000L) + 50L)
        }
    }
    val window = LocalShellWindow.current
    val snapshot = status
    val battery = snapshot?.batteryPercent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = statusDescription(clock, snapshot)
            },
    ) {
        if (clock.isNotEmpty()) {
            Text(clock, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
        }
        if (snapshot != null) {
            NetworkGlyph(snapshot)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BatteryGlyph(battery, snapshot.charging)
                if (showBatteryPercent && battery != null) {
                    Text("$battery%", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** What a screen reader says for the whole cluster; also the one place these facts become words. */
private fun statusDescription(clock: String, status: SystemStatusSnapshot?): String {
    val parts = ArrayList<String>(4)
    if (clock.isNotEmpty()) parts += clock
    if (status != null) {
        parts += when (status.network) {
            NetworkKind.WIFI -> if (status.validated) "Wi-Fi connected" else "Wi-Fi without internet"
            NetworkKind.ETHERNET -> "Wired network connected"
            NetworkKind.CELLULAR -> "Mobile data connected"
            NetworkKind.NONE -> "No network"
        }
        status.batteryPercent?.let { parts += "Battery $it percent" + if (status.charging) ", charging" else "" }
    }
    return parts.joinToString(", ")
}

@Composable
private fun NetworkGlyph(status: SystemStatusSnapshot) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stroke = remember(density) { Stroke(width = with(density) { 1.6.dp.toPx() }, cap = StrokeCap.Round) }
    val normal = MenuTokens.OnSurfaceMuted
    val attention = MenuTokens.Danger
    val kind = status.network
    val level = status.wifiLevel
    val lit = when {
        kind == NetworkKind.NONE -> 0
        level == null -> 3
        else -> ((level * 3 + 3) / 4).coerceIn(1, 3)
    }
    val tint = if (kind == NetworkKind.NONE || !status.validated) attention else normal
    Canvas(Modifier.size(width = 18.dp, height = 14.dp)) {
        val dim = normal.copy(alpha = 0.3f)
        if (kind == NetworkKind.WIFI || kind == NetworkKind.NONE) {
            // Three arcs over a dot, centred on the bottom middle.
            val cx = size.width / 2f
            val cy = size.height - 1.5.dp.toPx()
            for (i in 1..3) {
                val r = i * (size.height - 2.dp.toPx()) / 3f
                drawArc(
                    color = if (i <= lit) tint else dim,
                    startAngle = -135f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(2 * r, 2 * r),
                    style = stroke,
                )
            }
            drawCircle(if (lit > 0) tint else dim, radius = 1.4.dp.toPx(), center = Offset(cx, cy))
            if (kind == NetworkKind.NONE) {
                drawLine(
                    attention,
                    Offset(2.dp.toPx(), 1.dp.toPx()),
                    Offset(size.width - 2.dp.toPx(), size.height - 1.dp.toPx()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
        } else {
            // Wired and mobile: four rising bars, all lit while connected.
            val barW = 2.5.dp.toPx()
            val gap = (size.width - 4 * barW) / 3f
            for (i in 0 until 4) {
                val h = size.height * (i + 1) / 4f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun BatteryGlyph(percent: Int?, charging: Boolean) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stroke = remember(density) { Stroke(width = with(density) { 1.4.dp.toPx() }) }
    val tint = when {
        charging -> MenuTokens.Affirmative
        percent != null && percent <= LOW_BATTERY_PERCENT -> MenuTokens.Danger
        else -> MenuTokens.OnSurfaceMuted
    }
    Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
        val nubW = 2.dp.toPx()
        val bodyW = size.width - nubW
        val half = stroke.width / 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(half, half),
            size = Size(bodyW - stroke.width, size.height - stroke.width),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyW, size.height * 0.3f),
            size = Size(nubW, size.height * 0.4f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        if (percent != null) {
            val inset = stroke.width + 1.dp.toPx()
            val room = bodyW - 2 * inset
            drawRoundRect(
                color = tint,
                topLeft = Offset(inset, inset),
                size = Size((room * percent.coerceIn(0, 100) / 100f).coerceAtLeast(1.dp.toPx()), size.height - 2 * inset),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

private const val LOW_BATTERY_PERCENT = 15

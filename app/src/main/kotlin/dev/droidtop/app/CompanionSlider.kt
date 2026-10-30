package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.util.LatestWrites
import kotlin.math.roundToInt

/**
 * The companion's one slider row (docs/SPEC.md "The companion's tabs", Droidtop/tracker#786 S2a): the name and the
 * value on one line, then a drawn track between a step button at each end, the Quick Menu's step
 * ([dev.droidtop.library.settings.QuickTiles.sliderStep] for a catalog slider). A finger drags or taps the track; the
 * buttons step. Every catalog slider, every pinned slider, every volume and both brightnesses draw this; there is no
 * other. The value moves under the finger at once, and the write goes through [LatestWrites], off the main thread, so a
 * drag asks Android only for where it ends. A screen reader hears one node: the name, the value as [label] says it,
 * its range, and Lower and Raise as actions. [value] is the setting as last read; a new one replaces the shown value
 * when no finger is on the track.
 */
@Composable
internal fun CompanionSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    writeKey: String,
    write: suspend (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    label: (Float) -> String = { percentOf(it, range) },
) {
    var shown by remember(writeKey) { mutableFloatStateOf(value.coerceIn(range)) }
    var dragging by remember(writeKey) { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) shown = value.coerceIn(range) }
    val writeNow by rememberUpdatedState(write)
    fun set(next: Float) {
        val clamped = next.coerceIn(range)
        if (clamped == shown) return
        shown = clamped
        LatestWrites.app.submit(writeKey) { writeNow(clamped) }
    }
    val colors = MaterialTheme.colorScheme
    val said = label(shown)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = said
                progressBarRangeInfo = ProgressBarRangeInfo(shown, range)
                setProgress { target -> set(target); true }
                customActions = listOf(
                    CustomAccessibilityAction("Lower") { set(shown - step); true },
                    CustomAccessibilityAction("Raise") { set(shown + step); true },
                )
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(said, style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SliderStep("−") { set(shown - step) }
            val fraction = if (range.endInclusive > range.start) (shown - range.start) / (range.endInclusive - range.start) else 0f
            fun valueAt(x: Float, width: Int): Float =
                range.start + (x / width.coerceAtLeast(1)).coerceIn(0f, 1f) * (range.endInclusive - range.start)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .pointerInput(writeKey, range) {
                        detectTapGestures { offset -> set(valueAt(offset.x, size.width)) }
                    }
                    .pointerInput(writeKey, range) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> dragging = true; set(valueAt(offset.x, size.width)) },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ -> change.consume(); set(valueAt(change.position.x, size.width)) }
                    }
                    .drawBehind {
                        val track = 6.dp.toPx()
                        val top = (size.height - track) / 2
                        val radius = CornerRadius(track / 2)
                        drawRoundRect(colors.surfaceVariant, Offset(0f, top), Size(size.width, track), radius)
                        drawRoundRect(colors.primary, Offset(0f, top), Size(size.width * fraction, track), radius)
                        drawCircle(colors.primary, 10.dp.toPx(), Offset(size.width * fraction, size.height / 2))
                    },
            )
            SliderStep("+") { set(shown + step) }
        }
    }
}

/** A step button at one end of the track: 48dp. Its name is the row's (the row is one node for a screen reader). */
@Composable
private fun SliderStep(glyph: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A catalog slider's value as the shell's Settings says it: the number, or a percentage on a 0 to 100 scale. Pure. */
internal fun catalogSliderLabel(value: Float, min: Int, max: Int): String =
    if (min == 0 && max == 100) "${value.roundToInt()}%" else value.roundToInt().toString()

/** A value as a percentage of its range, rounded: "40%". Pure. */
internal fun percentOf(value: Float, range: ClosedFloatingPointRange<Float>): String {
    val span = range.endInclusive - range.start
    val percent = if (span > 0f) ((value - range.start) / span * 100f).roundToInt() else 0
    return "$percent%"
}

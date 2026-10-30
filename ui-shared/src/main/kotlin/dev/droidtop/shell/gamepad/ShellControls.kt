package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.droidtop.ui.shared.Motion

/**
 * The shell's one on/off switch, in Settings' rows and on the Quick Menu's tiles alike (DroidDeck's
 * ToggleSwitch, ui/SettingsWidgets.kt at 9310d19, in the theme's roles): a 52 by 30 track that fills
 * with the affirmative colour when on, its knob sliding to that end in the ink that reads on that fill
 * ([MenuTokens.OnAffirmative]). The colour answers at once; the
 * knob glides ([Motion.FocusMs]) and is moved in the layer phase, so a flip recomposes nothing.
 */
@Composable
fun ShellSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val knob = animateFloatAsState(if (on) 1f else 0f, Motion.tw(Motion.FocusMs, easing = Motion.Glide), label = "switch knob")
    Box(
        modifier = modifier
            .size(width = SwitchWidth, height = SwitchHeight)
            .clip(Corners.Pill)
            .background(if (on) MenuTokens.Affirmative else MenuTokens.Placeholder)
            .padding(SwitchInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .graphicsLayer { translationX = knob.value * (SwitchWidth - SwitchHeight).toPx() }
                .size(SwitchHeight - SwitchInset * 2)
                .clip(Corners.Pill)
                .background(if (on) MenuTokens.OnAffirmative else MenuTokens.OnSurface),
        )
    }
}

private val SwitchWidth = 52.dp
private val SwitchHeight = 30.dp
private val SwitchInset = 4.dp

/**
 * The shell's one slider, in Settings' rows and the Quick Menu alike (DroidDeck's ValueSlider drawing,
 * ui/SettingsWidgets.kt at 9310d19, in the theme's roles): a thin track, filled to [fraction] in the
 * accent while [selected] and in the value colour otherwise, with a round thumb at the fill's end.
 * Drawn in one pass; [modifier] gives it its width.
 */
@Composable
fun ShellSlider(fraction: Float, selected: Boolean, modifier: Modifier = Modifier) {
    val track = MenuTokens.Placeholder
    val fill = if (selected) MenuTokens.Accent else MenuTokens.Value
    androidx.compose.foundation.Canvas(modifier.height(SliderHeight)) {
        val thumb = SliderThumb.toPx()
        val y = size.height / 2f
        val start = androidx.compose.ui.geometry.Offset(thumb, y)
        val end = androidx.compose.ui.geometry.Offset(size.width - thumb, y)
        val at = androidx.compose.ui.geometry.Offset(start.x + (end.x - start.x) * fraction.coerceIn(0f, 1f), y)
        val line = SliderTrack.toPx()
        drawLine(track, start, end, line, androidx.compose.ui.graphics.StrokeCap.Round)
        if (fraction > 0f) drawLine(fill, start, at, line, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(fill, thumb, at)
    }
}

/**
 * The shell's one progress bar ([ProgressLook]): the shared job bar ([dev.droidtop.ui.shared.JobProgress],
 * Droidtop/tracker#643) in Gaming's roles, a thin track in the scrim role filled in the accent, Steam's black track
 * and blue fill. The fill glides between reports; a negative [fraction] (a job under way whose size is not known yet)
 * is the checking shimmer. [modifier] gives it its width. A download's row, a capsule being installed and Home's
 * download cards all draw it.
 */
@Composable
fun ShellProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    look: dev.droidtop.library.settings.JobLook = dev.droidtop.library.settings.JobLook.of(fraction)!!,
    parts: Int = 0,
) {
    dev.droidtop.ui.shared.JobProgress(fraction, look, shellJobColors(), modifier, height = ProgressLook.Height, parts = parts)
}

/**
 * The shell's one loading indicator (Droidtop/tracker#671): [ShellProgressBar]'s checking shimmer, for something
 * under way with no measure (a list being built, a source being asked). Every waiting place draws this, never a
 * stock spinner; [modifier] may give another width ([ProgressLook.InlineLoadingWidth] beside a line of text).
 */
@Composable
fun ShellLoading(modifier: Modifier = Modifier.width(ProgressLook.LoadingWidth)) {
    ShellProgressBar(-1f, modifier)
}

/** Gaming's colours for a job's bar and mark. */
@Composable
fun shellJobColors(): dev.droidtop.ui.shared.JobProgressColors {
    return dev.droidtop.ui.shared.JobProgressColors(
        track = MenuTokens.Scrim,
        fill = MenuTokens.Accent,
        done = MenuTokens.Affirmative,
        failed = MenuTokens.NoticeDanger,
    )
}

private val SliderHeight = 20.dp
private val SliderTrack = 4.dp
private val SliderThumb = 7.dp

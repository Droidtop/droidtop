package dev.droidtop.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.JobLook

/**
 * The colours a job's bar is drawn in, from the host's own theme: Gaming's scrim and accent, the companion's Material
 * roles, Standard's preference colours.
 */
data class JobProgressColors(val track: Color, val fill: Color, val done: Color, val failed: Color)

/**
 * The one job progress bar, every surface (docs/SPEC.md "Motion and visual status", Droidtop/tracker#643; owner,
 * 2026-10-24: "The current 'flickery numbers for download progress', for instance, is bad"). The fill glides to each
 * new [fraction] ([Motion.progress]) instead of jumping; [look] says how it moves: queued pulses dim, checking runs a
 * shimmer along the track, paused stands dimmed, done fills, failed turns the track to the danger colour and shakes
 * once. Every moving value is read while drawing, so a list of jobs is drawn again, never laid out again, as they
 * move; with motion off ([Motion.enabled]) it is the same bar standing still.
 *
 * [parts] from 2 to [JobLook.MAX_PARTS] draws the segmented form (Droidtop/tracker#738): a release in parts or several
 * files, one segment each, filled part by part. A screen reader hears the state's words ([JobLook.words]) and, while
 * it moves, the percent.
 */
@Composable
fun JobProgress(fraction: Float, look: JobLook, colors: JobProgressColors, modifier: Modifier = Modifier, height: Dp = 4.dp, parts: Int = 0) {
    val target = when (look) {
        JobLook.DONE -> 1f
        JobLook.CHECKING -> 0f
        else -> fraction.coerceIn(0f, 1f)
    }
    val fill = animateFloatAsState(target, Motion.progress(), label = "job-fill")
    val pulse = pulseOf(look == JobLook.QUEUED)
    val shimmer = shimmerOf(look == JobLook.CHECKING)
    val shake = shakeOf(look == JobLook.FAILED)
    val segments = parts.takeIf { it in 2..JobLook.MAX_PARTS } ?: 1
    val said = JobLook.words(look) + if (look == JobLook.ACTIVE || look == JobLook.LEVEL) ", ${(target * 100).toInt()} percent" else ""
    Canvas(
        modifier
            .height(height)
            .semantics { contentDescription = said }
            .graphicsLayer { translationX = shake.value * density },
    ) {
        val track = if (look == JobLook.FAILED) colors.failed.copy(alpha = 0.35f) else colors.track
        if (segments > 1) {
            drawSegments(segments, fill.value, track, if (look == JobLook.DONE) colors.done else if (look == JobLook.FAILED) colors.failed else colors.fill, if (look == JobLook.QUEUED) pulse.value else if (look == JobLook.PAUSED) 0.5f else 1f)
            return@Canvas
        }
        drawRect(track)
        val ink = when (look) {
            JobLook.DONE -> colors.done
            JobLook.FAILED -> colors.failed
            else -> colors.fill
        }
        val alpha = when (look) {
            JobLook.QUEUED -> pulse.value
            JobLook.PAUSED -> 0.5f
            else -> 1f
        }
        val width = size.width * fill.value
        if (width > 0f) drawRect(ink.copy(alpha = ink.alpha * alpha), size = Size(width, size.height))
        if (look == JobLook.CHECKING) {
            // A band of the fill crossing the track: under way, nothing to count yet.
            val band = size.width * 0.3f
            val x = -band + (size.width + band) * shimmer.value
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, colors.fill, Color.Transparent), startX = x, endX = x + band),
                topLeft = Offset(x, 0f),
                size = Size(band, size.height),
            )
        }
    }
}

/**
 * The mark beside a finished job: a check that draws itself in ([Motion.DoneMs]) when it is done, a cross in the danger
 * colour that shakes once when it failed; nothing for a job still under way.
 */
@Composable
fun JobMark(look: JobLook, colors: JobProgressColors, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    if (look != JobLook.DONE && look != JobLook.FAILED) return
    val drawn = remember(look) { Animatable(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(look) { drawn.animateTo(1f, Motion.tw(Motion.DoneMs, easing = Motion.Glide)) }
    val shake = shakeOf(look == JobLook.FAILED)
    val said = JobLook.words(look)
    Canvas(modifier.size(size).semantics { contentDescription = said }.graphicsLayer { translationX = shake.value * density }) {
        val stroke = Stroke(width = this.size.minDimension * 0.14f, cap = StrokeCap.Round)
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            if (look == JobLook.DONE) {
                moveTo(w * 0.18f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.76f)
                lineTo(w * 0.84f, h * 0.26f)
            } else {
                moveTo(w * 0.24f, h * 0.24f)
                lineTo(w * 0.76f, h * 0.76f)
                moveTo(w * 0.76f, h * 0.24f)
                lineTo(w * 0.24f, h * 0.76f)
            }
        }
        val ink = if (look == JobLook.DONE) colors.done else colors.failed
        if (look == JobLook.DONE && drawn.value < 1f) {
            val measure = PathMeasure().apply { setPath(path, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * drawn.value, part, true)
            drawPath(part, ink, style = stroke)
        } else {
            drawPath(path, ink.copy(alpha = ink.alpha * drawn.value), style = stroke)
        }
    }
}

/**
 * The ring form of [JobProgress], for a place too small for a bar: an entry that stands for many jobs (Downloads and
 * Updates in the left menu, Droidtop/tracker#643: "a filling ring while active, a dot when something needs the
 * person"). The arc glides to [fraction] as the bar's fill does; with no fraction ([JobLook.CHECKING]) a short arc
 * turns, standing still with motion off.
 */
@Composable
fun JobRing(fraction: Float, look: JobLook, colors: JobProgressColors, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val sweep = animateFloatAsState(if (look == JobLook.CHECKING) 0.25f else fraction.coerceIn(0f, 1f), Motion.progress(), label = "job-ring")
    val turn = shimmerOf(look == JobLook.CHECKING)
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.16f, cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(colors.track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
        val start = if (look == JobLook.CHECKING) 360f * turn.value - 90f else -90f
        drawArc(colors.fill, start, 360f * sweep.value, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
    }
}

/**
 * The segmented bar: [count] segments with a gap between, the first ones full and the one under way filled as far as
 * [fill] (0 to 1 of the whole) reaches into it. [alpha] dims the fill as the plain bar's does.
 */
private fun DrawScope.drawSegments(count: Int, fill: Float, track: Color, ink: Color, alpha: Float) {
    val gap = minOf(2.dp.toPx(), size.width / (count * 4f))
    val each = (size.width - gap * (count - 1)) / count
    val filled = fill.coerceIn(0f, 1f) * count
    for (i in 0 until count) {
        val x = i * (each + gap)
        drawRect(track, topLeft = Offset(x, 0f), size = Size(each, size.height))
        val part = (filled - i).coerceIn(0f, 1f)
        if (part > 0f) drawRect(ink.copy(alpha = ink.alpha * alpha), topLeft = Offset(x, 0f), size = Size(each * part, size.height))
    }
}

/** Figures of equal width, so a percent or a time that changes does not make the text beside it jitter. */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

/** A queued job's slow pulse between dim and half, or a steady dim with motion off or when [on] is false. */
@Composable
private fun pulseOf(on: Boolean): State<Float> {
    if (!on || !Motion.enabled) return remember { mutableFloatStateOf(0.35f) }
    return rememberInfiniteTransition(label = "job-pulse").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(Motion.AmbientPulseMs, easing = Motion.QuickOut), RepeatMode.Reverse),
        label = "job-pulse-alpha",
    )
}

/** Where the checking shimmer is along the track, 0 to 1; standing at the middle with motion off. */
@Composable
private fun shimmerOf(on: Boolean): State<Float> {
    if (!on || !Motion.enabled) return remember { mutableFloatStateOf(0.5f) }
    return rememberInfiniteTransition(label = "job-shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.ShimmerMs, easing = LinearEasing), RepeatMode.Restart),
        label = "job-shimmer-x",
    )
}

/** A failed job's one shake, in dp from where it stands, played when [on] turns true; still with motion off. */
@Composable
private fun shakeOf(on: Boolean): State<Float> {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(on) {
        if (on && Motion.enabled) {
            offset.animateTo(
                0f,
                keyframes {
                    durationMillis = Motion.ShakeMs
                    -6f at Motion.ShakeMs / 6
                    5f at Motion.ShakeMs * 2 / 6
                    -3f at Motion.ShakeMs * 3 / 6
                    2f at Motion.ShakeMs * 4 / 6
                },
            )
        }
    }
    return offset.asState()
}

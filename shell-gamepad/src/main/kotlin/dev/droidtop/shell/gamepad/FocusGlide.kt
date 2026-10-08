package dev.droidtop.shell.gamepad

// Ported from DroidDeck (ui/FocusGlide.kt at 9310d19, GPL-3.0, see NOTICE.md). Changed for droidtop:
// a source is claimed by droidtop's one cursor (`selected` while a pad drives, PadModality), not by
// Compose focus or hover; the ring wears Steam's measured look (2dp, the theme's accent at 60
// percent, landing from 12dp outside, breathing) instead of a fixed colour; capsules carry it a
// little outside their edge; the ring draws in an overlay of its own so a moving or breathing ring
// redraws nothing but itself; and every spec comes from droidtop's Motion roles, so the Animations
// switch turns it into a ring that simply moves.

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.droidtop.shell.gamepad.input.PadModality
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/*
 * One focus ring per window that slides from control to control (docs/SPEC.md "Gaming motion
 * and focus"). When the cursor moves, the edge on the side it is heading springs ahead and the
 * trailing edge catches up a beat later, so the ring stretches and snaps onto the new control's
 * shape; a held direction runs it along as one piece; a long jump carries it across as a drop.
 * Where no host is around (a dialog window nobody gave one), the ring is drawn on the control
 * itself, still and without the glide.
 */

/** A control that can carry the ring: where it is, its shape, and how far outside it the ring sits. */
internal class GlideSource {
    var coords: LayoutCoordinates? = null
    var shape: Shape = androidx.compose.ui.graphics.RectangleShape
    var outset: Dp = 0.dp
}

internal class FocusGlide(val view: View) {
    var host: LayoutCoordinates? = null

    /** Controls that hold the cursor, the latest last (two screens crossfading can each hold one). */
    val hot = mutableStateListOf<GlideSource>()

    /** Bumped when the current control moves under the ring (a scroll, a relayout). */
    var moves by mutableIntStateOf(0)

    val current: GlideSource? get() = hot.lastOrNull()

    fun claim(s: GlideSource) {
        hot.remove(s)
        hot.add(s)
    }

    fun release(s: GlideSource) {
        hot.remove(s)
    }

    fun moved(s: GlideSource) {
        if (hot.lastOrNull() === s) moves++
    }

    /** [s]'s ring rectangle in the host, cut to what is on screen; null when it is not laid out or scrolled away. */
    fun boundsOf(s: GlideSource, density: Density): Rect? {
        val h = host ?: return null
        val c = s.coords ?: return null
        if (!h.isAttached || !c.isAttached) return null
        val b = h.localBoundingBoxOf(c, clipBounds = true)
        if (b.width < 1f || b.height < 1f) return null
        val o = with(density) { s.outset.toPx() }
        return b.inflate(o)
    }
}

internal val LocalFocusGlide = staticCompositionLocalOf<FocusGlide?> { null }

private fun cornerOf(s: GlideSource, size: Size, dir: LayoutDirection, density: Density): Float {
    val outset = with(density) { s.outset.toPx() }
    return when (val o = s.shape.createOutline(size, dir, density)) {
        is Outline.Rounded -> o.roundRect.topLeftCornerRadius.x + outset
        is Outline.Rectangle -> outset
        is Outline.Generic -> minOf(size.width, size.height) / 2f + outset
    }
}

/**
 * The ring on one control: claims the window's sliding ring while [shown], or, in a window with no
 * [FocusGlideHost], draws a still ring here. [outset] puts the ring that far outside the control's
 * edge (a capsule's art keeps its edge clear, as Steam's does).
 */
internal fun Modifier.focusRing(shown: Boolean, shape: Shape, outset: Dp = 0.dp): Modifier = composed {
    val glide = LocalFocusGlide.current
    if (glide == null || glide.view !== LocalView.current) {
        if (!shown) return@composed Modifier
        val color = MenuTokens.Accent
        return@composed Modifier.drawWithContent {
            drawContent()
            val w = MenuTokens.FocusRingWidth.toPx()
            val o = outset.toPx()
            val inner = Size(size.width + 2 * o - w, size.height + 2 * o - w)
            translate(left = w / 2f - o, top = w / 2f - o) {
                drawOutline(
                    shape.createOutline(inner, layoutDirection, this),
                    color.copy(alpha = color.alpha * FocusLook.RingAlpha),
                    style = Stroke(w),
                )
            }
        }
    }
    val src = remember { GlideSource() }
    src.shape = shape
    src.outset = outset
    DisposableEffect(glide, shown) {
        if (shown) glide.claim(src)
        onDispose { glide.release(src) }
    }
    Modifier.onGloballyPositioned {
        src.coords = it
        glide.moved(src)
    }
}

/** Leading edge on a move: quick and a little loose. */
private fun lead(): AnimationSpec<Float> = Motion.sp(0.62f, 700f)

/** Trailing edge: slower, settles without a bounce to speak of. */
private fun trail(): AnimationSpec<Float> = Motion.sp(0.78f, 360f)

/** The edges across from both: between the two. */
private fun side(): AnimationSpec<Float> = Motion.sp(0.7f, 520f)

/** A held direction: every edge together, firm. */
private fun held(): AnimationSpec<Float> = Motion.sp(0.9f, 900f)

/** Following a control that moves under the ring (a scroll): tight, no wobble. */
private fun follow(): AnimationSpec<Float> = Motion.sp(1f, 2400f)

/**
 * Hosts the sliding focus ring for everything inside it, one per window: the shell, each side
 * panel, each menu panel, a game's page. The ring is drawn over [content] in the host's space by
 * an overlay of its own, so put the host inside anything that moves as a whole (a sheet that
 * slides in). The ring takes the theme's accent ([MenuTokens.Accent]).
 *
 * Work is bounded: the host re-measures its control every frame only for [Motion.GlideSettleMs]
 * after something changes (a lift or a slide moves a control without a relayout), and the
 * breath, which runs [Motion.RingBreathCycles] times and then rests, invalidates only the
 * overlay's own drawing, never the content under it.
 */
@Composable
internal fun FocusGlideHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val glide = remember(view) { FocusGlide(view) }
    val edges = remember { List(4) { Animatable(0f) } } // left, top, right, bottom
    val corner = remember { Animatable(0f) }
    /** How solid the ring is: 1 while it travels as a drop, 0 (an outline) on a control. */
    val solid = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    /** 0 is the ring at full strength, 1 the bottom of a breath. */
    val breath = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // An edge each, the corner, a droplet carrying all of them, and the breath.
    val jobs = remember { arrayOfNulls<Job>(7) }

    LaunchedEffect(glide, Motion.enabled) {
        var shown: GlideSource? = null
        var movedAt = 0L
        var dirSpecs: List<AnimationSpec<Float>> = List(4) { follow() }
        var delays = LongArray(4)
        var lastMove = 0L
        fun go(i: Int, v: Float, spec: AnimationSpec<Float>, wait: Long) {
            // A drop cut short by another move turns back into an outline on the way.
            if (jobs[5]?.isActive == true || solid.targetValue > 0f) scope.launch { solid.animateTo(0f, Motion.tw(120)) }
            jobs[5]?.cancel()
            // Picks up the speed it is going at: restarting from rest on every step of a held
            // direction is what makes a ring stutter.
            val speed = edges[i].velocity
            jobs[i]?.cancel()
            jobs[i] = scope.launch {
                if (wait > 0) delay(Motion.ms(wait.toInt()))
                edges[i].animateTo(v, spec, initialVelocity = if (wait > 0) 0f else speed)
            }
        }
        /** Once it has landed, the ring breathes a few times and then rests at full strength. */
        fun breathe() {
            jobs[6]?.cancel()
            jobs[6] = scope.launch {
                breath.snapTo(0f)
                if (!Motion.enabled) return@launch
                delay(Motion.ms(Motion.RingLandMs))
                repeat(Motion.RingBreathCycles) {
                    breath.animateTo(1f, Motion.tw(Motion.RingBreathMs / 2, easing = Motion.Glide))
                    breath.animateTo(0f, Motion.tw(Motion.RingBreathMs / 2, easing = Motion.Glide))
                }
            }
        }
        fun rectEdges(b: Rect) = listOf(b.left, b.top, b.right, b.bottom)
        fun sizeOf(s: GlideSource, b: Rect): Size =
            s.coords?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: b.size
        /** Pinch into a solid drop where it is, carry it over, and open it onto [b] as it arrives. */
        fun droplet(s: GlideSource, b: Rect) {
            jobs.forEach { it?.cancel() }
            val endCorner = cornerOf(s, sizeOf(s, b), dir, density)
            jobs[5] = scope.launch {
                val r = with(density) { 6.dp.toPx() }
                fun dot(c: Offset) = listOf(c.x - r, c.y - r, c.x + r, c.y + r)
                val here = Offset((edges[0].value + edges[2].value) / 2f, (edges[1].value + edges[3].value) / 2f)
                coroutineScope {
                    launch { solid.animateTo(1f, Motion.tw(80)) }
                    launch { corner.animateTo(r, Motion.tw(90)) }
                    dot(here).forEachIndexed { i, v -> launch { edges[i].animateTo(v, Motion.tw(90)) } }
                }
                val distance = hypot(b.center.x - here.x, b.center.y - here.y) / density.density
                val travel = (180 + distance * 0.12f).toInt().coerceIn(220, 380)
                dot(b.center).forEachIndexed { i, v ->
                    jobs[i] = launch { edges[i].animateTo(v, Motion.tw(travel, easing = Motion.Glide)) }
                }
                // Takes the control's shape as it arrives, not after: no resting as a dot.
                delay(Motion.ms((travel * 0.78f).toInt()))
                launch { solid.animateTo(0f, Motion.tw(150)) }
                launch { corner.animateTo(endCorner, Motion.sp(0.8f, 600f)) }
                rectEdges(b).forEachIndexed { i, v -> jobs[i] = launch { edges[i].animateTo(v, Motion.sp(0.7f, 750f)) } }
            }
        }
        fun cornerTo(s: GlideSource, b: Rect, snap: Boolean) {
            val r = cornerOf(s, sizeOf(s, b), dir, density)
            jobs[4]?.cancel()
            jobs[4] = scope.launch { if (snap) corner.snapTo(r) else corner.animateTo(r, Motion.sp(0.8f, 500f)) }
        }
        snapshotFlow { glide.current to glide.moves }.collectLatest { (src, _) ->
            if (src == null) {
                // The cursor passing between two controls can let go of one a moment before the
                // next takes it: wait a beat, so that reads as a move and not a fade out and in.
                delay(60)
                scope.launch { alpha.animateTo(0f, Motion.tw(Motion.RingFadeMs)) }
                jobs[6]?.cancel()
                shown = null
                return@collectLatest
            }
            if (!Motion.enabled) {
                jobs.forEach { it?.cancel() }
                solid.snapTo(0f)
                breath.snapTo(0f)
                val b = glide.boundsOf(src, density)
                if (b == null) {
                    alpha.snapTo(0f)
                } else {
                    rectEdges(b).forEachIndexed { i, v -> edges[i].snapTo(v) }
                    corner.snapTo(cornerOf(src, sizeOf(src, b), dir, density))
                    alpha.snapTo(1f)
                }
                shown = src
                return@collectLatest
            }
            var first = src !== shown
            val start = System.nanoTime()
            var last: Rect? = null
            while (true) {
                val b = glide.boundsOf(src, density)
                if (b == null) {
                    if (first) scope.launch { alpha.animateTo(0f, Motion.tw(Motion.RingFadeMs)) }
                } else if (first) {
                    var dropped = false
                    val wasShowing = shown != null && alpha.targetValue > 0f
                    shown = src
                    first = false
                    if (!wasShowing) {
                        // Landing: it starts well outside the control and closes onto it as it
                        // fades up.
                        val out = with(density) { Motion.RingLandFromDp.dp.toPx() }
                        jobs.forEach { it?.cancel() }
                        solid.snapTo(0f)
                        rectEdges(b.inflate(out)).forEachIndexed { i, v -> edges[i].snapTo(v) }
                        dirSpecs = List(4) { Motion.tw(Motion.RingLandMs, easing = Motion.Glide) }
                        delays = LongArray(4)
                        cornerTo(src, b, snap = true)
                        scope.launch { alpha.animateTo(1f, Motion.tw(Motion.RingLandMs, easing = Motion.Glide)) }
                    } else {
                        // Moving: the edges on the side it is heading lead.
                        val dx = b.center.x - (edges[0].value + edges[2].value) / 2f
                        val dy = b.center.y - (edges[1].value + edges[3].value) / 2f
                        val leadIdx = glideLeadEdge(dx, dy)
                        val trailIdx = (leadIdx + 2) % 4
                        val now = System.nanoTime()
                        val isHeld = glideIsHeld((now - lastMove) / 1_000_000)
                        lastMove = now
                        scope.launch { alpha.animateTo(1f, Motion.tw(120)) }
                        dropped = glideIsFar(isHeld, hypot(dx, dy) / density.density)
                        if (dropped) {
                            droplet(src, b)
                        } else {
                            dirSpecs = if (isHeld) {
                                List(4) { held() }
                            } else {
                                List(4) { i -> when (i) { leadIdx -> lead(); trailIdx -> trail(); else -> side() } }
                            }
                            delays = LongArray(4) { i -> if (i == trailIdx && !isHeld) Motion.GlideTrailDelayMs.toLong() else 0L }
                            cornerTo(src, b, snap = false)
                        }
                    }
                    movedAt = System.nanoTime()
                    if (!dropped) rectEdges(b).forEachIndexed { i, v -> go(i, v, dirSpecs[i], delays[i]) }
                    breathe()
                    last = b
                } else if (b != last && jobs[5]?.isActive != true) {
                    // It moved under the ring: keep the stretch while the move is fresh, else follow.
                    val fresh = (System.nanoTime() - movedAt) / 1_000_000 < Motion.GlideSettleMs
                    rectEdges(b).forEachIndexed { i, v -> go(i, v, if (fresh) dirSpecs[i] else follow(), 0L) }
                    if (last == null) scope.launch { alpha.animateTo(1f, Motion.tw(120)) }
                    last = b
                }
                // Layer animations (a capsule lifting, a row sliding) move it without a relayout,
                // so look again each frame for a moment after anything changes, then stop.
                if ((System.nanoTime() - start) / 1_000_000 > Motion.GlideSettleMs) break
                withFrameNanos { }
            }
        }
    }

    Box(modifier.onGloballyPositioned { glide.host = it }) {
        CompositionLocalProvider(LocalFocusGlide provides glide) { content() }
        // The ring's own overlay: animating it redraws this and nothing under it. Hidden while a
        // finger drives (PadModality): every claim is released then anyway.
        Spacer(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val a = alpha.value
                    if (a <= 0.01f || !PadModality.showsFocus) return@drawBehind
                    val accent = MenuTokens.Accent
                    val ringAlpha = a * FocusLook.ringAlpha(breath.value)
                    val w = MenuTokens.FocusRingWidth.toPx()
                    val l = edges[0].value
                    val t = edges[1].value
                    val r = edges[2].value
                    val b = edges[3].value
                    val sw = (r - l - w).coerceAtLeast(0f)
                    val sh = (b - t - w).coerceAtLeast(0f)
                    val rad = (corner.value - w / 2f).coerceIn(0f, minOf(sw, sh) / 2f)
                    val f = solid.value
                    if (f > 0.01f) {
                        drawRoundRect(
                            accent,
                            topLeft = Offset(l, t),
                            size = Size(r - l, b - t),
                            cornerRadius = CornerRadius(corner.value),
                            alpha = ringAlpha * f,
                        )
                    }
                    if (f < 0.99f) {
                        drawRoundRect(
                            accent,
                            topLeft = Offset(l + w / 2f, t + w / 2f),
                            size = Size(sw, sh),
                            cornerRadius = CornerRadius(rad),
                            style = Stroke(w),
                            alpha = ringAlpha,
                        )
                    }
                },
        )
    }
}

/** Which edge leads a move of ([dx], [dy]): 0 left, 1 top, 2 right, 3 bottom. Pure. */
internal fun glideLeadEdge(dx: Float, dy: Float): Int =
    if (abs(dx) >= abs(dy)) (if (dx >= 0) 2 else 0) else (if (dy >= 0) 3 else 1)

/** Whether a move [sinceLastMs] after the last one is a held direction. Pure. */
internal fun glideIsHeld(sinceLastMs: Long): Boolean = sinceLastMs < Motion.GlideRepeatMs

/** Whether a move of [distanceDp] travels as a drop: a far, single press. Pure. */
internal fun glideIsFar(held: Boolean, distanceDp: Float): Boolean = !held && distanceDp > Motion.GlideFarDp

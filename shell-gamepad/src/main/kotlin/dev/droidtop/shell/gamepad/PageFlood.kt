package dev.droidtop.shell.gamepad

// Ported from DroidDeck (ui/PageFlood.kt and ui/LaunchFlood.kt at 9310d19, GPL-3.0, see NOTICE.md). Changed for
// droidtop: one flood for both moves, a game's page growing out of its capsule and a launch growing out of the
// page's Play, drawn in the theme's roles (the card for a page, the launch colour for Play) instead of fixed colours;
// the origins are SCREEN rectangles, because the page and the launch screen are other windows than the control they
// grow out of; and every spec is a droidtop Motion role, so the Animations switch turns both into a plain cut.

import android.os.SystemClock
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * Grow-out transitions (docs/SPEC.md "Gaming motion and focus", owner 2026-10-08: DroidDeck's signature motion,
 * behind the Animations switch). A game's page grows out of the capsule it was opened from: the capsule's plate
 * stretches out over the screen on loose springs, the far edges first, and the page rises into it once it is
 * covered; B draws it back down into the capsule. Play on the page grows into the launch screen the same way, in the
 * launch colour, and the launch screen appears as it fades. Drawn in the draw phase from a handful of animated
 * values; nothing recomposes while it runs, and with motion off nothing runs at all.
 */

/** Where a flood grows out of: the capsule under the cursor, and the Play button just pressed. Main thread only. */
internal object FloodOrigin {
    private var capsuleId: String? = null
    private var capsule: WeakReference<LayoutCoordinates>? = null
    private var capsuleView: WeakReference<View>? = null
    private var launch: Rect? = null
    private var launchAt = 0L

    /** The capsule holding the cursor reports where it is (only that one: PcCapsule while selected). */
    fun trackCapsule(id: String, coords: LayoutCoordinates, view: View) {
        if (capsuleId != id || capsule?.get() !== coords) {
            capsuleId = id
            capsule = WeakReference(coords)
            capsuleView = WeakReference(view)
        }
    }

    /** Where game [id]'s capsule is on screen now, if it is the one under the cursor and still laid out. */
    fun capsuleRect(id: String): Rect? {
        if (capsuleId != id) return null
        val coords = capsule?.get()?.takeIf { it.isAttached } ?: return null
        val view = capsuleView?.get() ?: return null
        return screenRect(coords, view)
    }

    /** Play was pressed at [r] (screen px) and a launch follows. */
    fun markLaunch(r: Rect) {
        launch = r
        launchAt = SystemClock.uptimeMillis()
    }

    /** The Play a launch starting now came from, once; stale after [LAUNCH_ORIGIN_MS], so a launch from elsewhere never floods. */
    fun takeLaunch(): Rect? {
        val r = launch?.takeIf { SystemClock.uptimeMillis() - launchAt < LAUNCH_ORIGIN_MS }
        launch = null
        return r
    }

    private const val LAUNCH_ORIGIN_MS = 1500L
}

/** [coords]' bounds on the screen, [view] being any view of its window. */
internal fun screenRect(coords: LayoutCoordinates, view: View): Rect {
    val at = IntArray(2)
    view.rootView.getLocationOnScreen(at)
    return coords.boundsInWindow().translate(Offset(at[0].toFloat(), at[1].toFloat()))
}

/** How far [edge] (0 left, 1 top, 2 right, 3 bottom) of [from] has to travel to reach [size]'s, as a share of the farthest. Pure. */
internal fun floodLead(from: Rect, size: Size, edge: Int): Float {
    val travel = floatArrayOf(from.left, from.top, size.width - from.right, size.height - from.bottom)
    val far = travel.maxOf { it.coerceAtLeast(0f) }.coerceAtLeast(1f)
    return travel[edge].coerceAtLeast(0f) / far
}

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * The control at [from] with its edges [l], [t], [r], [b] of the way to the box's: sinking a touch with the page
 * behind, blobby in flight, square once it fills the box.
 */
private fun DrawScope.drawFlood(from: Rect, l: Float, t: Float, r: Float, b: Float, fill: Color, outline: Color?, alpha: Float, cornerAtRest: Float) {
    val mean = (l.coerceIn(0f, 1f) + t.coerceIn(0f, 1f) + r.coerceIn(0f, 1f) + b.coerceIn(0f, 1f)) / 4f
    val k = 1f - 0.05f * mean
    val cx = size.width / 2f
    val cy = size.height / 2f
    val left = mix(cx + (from.left - cx) * k, 0f, l)
    val top = mix(cy + (from.top - cy) * k, 0f, t)
    val right = mix(cx + (from.right - cx) * k, size.width, r)
    val bottom = mix(cy + (from.bottom - cy) * k, size.height, b)
    val w = (right - left).coerceAtLeast(0f)
    val h = (bottom - top).coerceAtLeast(0f)
    val blob = sin(PI * mean).toFloat().coerceAtLeast(0f)
    val corner = mix(cornerAtRest * (1f - mean), minOf(w, h) * 0.42f, blob)
    drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(corner), alpha = alpha)
    // The control's outline, kept while it is still the size of one.
    val edge = (1f - mean * 3f).coerceIn(0f, 1f)
    if (outline != null && edge > 0f) {
        drawRoundRect(outline, Offset(left, top), Size(w, h), CornerRadius(corner), alpha = alpha * edge, style = Stroke(1.dp.toPx()))
    }
}

/**
 * [content], a page opened from the control at [from] (screen px): the control's plate stretching over the box
 * until it is covered, then the page rising in as the plate fades. Once [leaving], the reverse, and [onLeft] when
 * it has drawn back into the control. The window's focus ring keeps out of it until it is done. Null [from], or
 * motion off, is the page as it is.
 */
@Composable
internal fun PageFlood(from: Rect?, leaving: Boolean, onLeft: () -> Unit, content: @Composable () -> Unit) {
    val flooding = remember { from != null && Motion.enabled }
    if (!flooding || from == null) {
        if (leaving) LaunchedEffect(Unit) { onLeft() }
        content()
        return
    }
    val view = LocalView.current
    val glide = LocalFocusGlide.current
    val fill = MenuTokens.Card
    val line = MenuTokens.CardOutline
    val left by rememberUpdatedState(onLeft)
    val edges = remember { List(4) { Animatable(0f) } }
    val shown = remember { Animatable(0f) }
    val tile = remember { Animatable(1f) }
    var local by remember { mutableStateOf<Rect?>(null) }
    var box by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(local != null, leaving) {
        val origin = local ?: return@LaunchedEffect
        glide?.hidden = true
        coroutineScope {
            if (!leaving) {
                val runs = edges.mapIndexed { i, edge ->
                    val lead = floodLead(origin, box, i)
                    launch {
                        delay(Motion.ms((150 * (1f - lead)).toInt()))
                        edge.animateTo(1f, Motion.sp(0.5f, mix(150f, 260f, lead)))
                    }
                }
                snapshotFlow { edges.all { it.value >= 0.995f } }.first { it }
                launch { tile.animateTo(0f, Motion.tw(360, delay = 60)) }
                shown.animateTo(1f, Motion.tw(320))
                runs.forEach { it.join() }
            } else {
                launch { shown.animateTo(0f, Motion.tw(140)) }
                tile.animateTo(1f, Motion.tw(140))
                edges.mapIndexed { i, edge ->
                    val lead = floodLead(origin, box, i)
                    launch {
                        delay(Motion.ms((120 * (1f - lead)).toInt()))
                        edge.animateTo(0f, Motion.sp(1f, mix(170f, 260f, lead)))
                    }
                }.forEach { it.join() }
                tile.animateTo(0f, Motion.tw(120))
                left()
            }
        }
        glide?.hidden = false
    }
    DisposableEffect(Unit) { onDispose { glide?.hidden = false } }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned {
                if (local == null) {
                    box = Size(it.size.width.toFloat(), it.size.height.toFloat())
                    local = from.translate(-screenRect(it, view).topLeft)
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = shown.value
                    translationY = (1f - shown.value) * PAGE_RISE_DP.dp.toPx()
                },
        ) { content() }
        Canvas(Modifier.fillMaxSize()) {
            val origin = local ?: return@Canvas
            if (tile.value > 0.01f) {
                drawFlood(origin, edges[0].value, edges[1].value, edges[2].value, edges[3].value, fill, line, tile.value, Corners.CrispRadius.toPx())
            }
        }
    }
}

/** How far a page rises as it appears over its flood. */
private const val PAGE_RISE_DP = 14

/**
 * A launch growing out of the Play at [from] (screen px) in [fill]: each edge on its own loose spring, the far ones
 * first, until the box is covered; then [onCovered], and the flood fades off what the box now shows. Swallows
 * nothing: the launch screen under it already takes no presses.
 */
@Composable
internal fun LaunchFlood(from: Rect, fill: Color, cornerAtRest: Dp, onCovered: () -> Unit) {
    val view = LocalView.current
    val covered by rememberUpdatedState(onCovered)
    val edges = remember { List(4) { Animatable(0f) } }
    val fade = remember { Animatable(1f) }
    var local by remember { mutableStateOf<Rect?>(null) }
    var box by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(local != null) {
        val origin = local ?: return@LaunchedEffect
        coroutineScope {
            val runs = edges.mapIndexed { i, edge ->
                val lead = floodLead(origin, box, i)
                launch {
                    delay(Motion.ms((180 * (1f - lead)).toInt()))
                    edge.animateTo(1f, Motion.sp(0.48f, mix(110f, 190f, lead)))
                }
            }
            snapshotFlow { edges.all { it.value >= 0.995f } }.first { it }
            covered()
            runs.forEach { it.join() }
            fade.animateTo(0f, Motion.tw(LAUNCH_FLOOD_FADE_MS))
        }
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                if (local == null) {
                    box = Size(it.size.width.toFloat(), it.size.height.toFloat())
                    local = from.translate(-screenRect(it, view).topLeft)
                }
            },
    ) {
        val origin = local ?: return@Canvas
        // An underdamped edge swings back a little short of the box after reaching it; once every edge has been
        // there the box stays covered, or the page behind would show through in a sliver.
        val all = edges.all { it.value >= 0.995f } || fade.value < 1f
        fun e(i: Int) = if (all) maxOf(edges[i].value, 1f) else edges[i].value
        if (fade.value > 0.01f) drawFlood(origin, e(0), e(1), e(2), e(3), fill, null, fade.value, cornerAtRest.toPx())
    }
}

/** How long the launch colour takes to fade off the launch screen once it has covered it. */
internal const val LAUNCH_FLOOD_FADE_MS = 360

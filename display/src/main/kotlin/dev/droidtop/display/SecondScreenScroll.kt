package dev.droidtop.display

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A second-screen page that scrolls from a vertical drag starting ANYWHERE on it (docs/SPEC.md "The
 * companion's tabs", Droidtop/tracker#328): [verticalScroll] plus [dragAnywhereScroll]. Every scrolling page
 * of the companion uses this, never a bare `verticalScroll`.
 */
fun Modifier.secondScreenScroll(state: ScrollState): Modifier = composed {
    val fling = ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    Modifier.dragAnywhereScroll(state, fling, scope).verticalScroll(state)
}

/**
 * Takes a vertical drag for the page before anything under the finger can keep it.
 *
 * A plain `verticalScroll` only scrolls when the drag reaches it unconsumed, and a finger rarely starts on
 * empty page: it lands on a rail capsule, a pill, a section heading or an Android widget. A rail (a sideways
 * `LazyRow`) claims the drag as soon as its sideways travel passes the touch slop, and a thumb's arc on a
 * handheld held level moves sideways about as much as it moves up, so the rail often won first; an Android
 * widget (an `AndroidView`) takes the whole stream from its first touch, so nothing above it ever scrolls.
 *
 * This watches each gesture in the [PointerEventPass.Initial] pass, which reaches the page before any of its
 * children. Once the finger's whole travel is past the touch slop and at least as far up or down as sideways,
 * the drag is the page's, even if a rail started scrolling on its first sideways movement: every later change
 * is consumed there (children see it consumed and stop or cancel their press, an Android view gets a cancel),
 * the page scrolls by the finger's travel, and the release flings with the finger's speed. A drag that stays
 * mostly sideways never becomes the page's, so a rail still scrolls sideways; a touch that never passes the
 * slop is a tap and reaches its target untouched. A new touch stops a running fling.
 */
fun Modifier.dragAnywhereScroll(state: ScrollState, fling: FlingBehavior, scope: CoroutineScope): Modifier =
    pointerInput(state, fling, scope) {
        var flingJob: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            flingJob?.cancel()
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var travel = Offset.Zero
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (claimed) {
                        change.consume()
                        val velocity = -tracker.calculateVelocity().y
                        flingJob = scope.launch { state.scroll { with(fling) { performFling(velocity) } } }
                    }
                    break
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChangeIgnoreConsumed()
                if (!claimed) {
                    travel += delta
                    // A drag that went sideways first may already be scrolling a rail; it becomes the page's
                    // as soon as it has gone at least as far up or down, and the rail then sees it consumed.
                    if (!pageTakesDrag(travel, slop)) continue
                    claimed = true
                }
                state.dispatchRawDelta(-delta.y)
                change.consume()
            }
        }
    }

/** Whether a drag that has [travel]led so far is the page's: past the slop, and at least as far up or down as sideways. Pure. */
fun pageTakesDrag(travel: Offset, slop: Float): Boolean = abs(travel.y) > slop && abs(travel.y) >= abs(travel.x)

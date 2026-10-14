package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion trackpad as a pointer on another screen (PointerInjection.kt), as pure logic. */
class PointerInjectionTest {
    private class Calls : PointerInjector {
        val log = mutableListOf<String>()
        override fun moveCursor(x: Float, y: Float) { log += "cursor ${x.toInt()},${y.toInt()}" }
        override fun tap(x: Float, y: Float) { log += "tap ${x.toInt()},${y.toInt()}" }
        override fun contextClick(x: Float, y: Float) { log += "context ${x.toInt()},${y.toInt()}" }
        override fun dragStart(x: Float, y: Float) { log += "dragStart ${x.toInt()},${y.toInt()}" }
        override fun dragTo(x: Float, y: Float) { log += "dragTo ${x.toInt()},${y.toInt()}" }
        override fun dragEnd(x: Float, y: Float) { log += "dragEnd ${x.toInt()},${y.toInt()}" }
        override fun scroll(x: Float, y: Float, dxPx: Float, dyPx: Float) { log += "scroll ${x.toInt()},${y.toInt()} ${dxPx.toInt()},${dyPx.toInt()}" }
    }

    // A 1920x1080 screen: 12 px per mm of finger travel at unity, 0.4 of that for a slow finger.
    private fun sink(calls: Calls, size: Pair<Int, Int>? = 1920 to 1080, speed: Float = 0f, natural: Boolean = true) =
        PointerTrackpadSink(calls, { size }, userSpeed = speed, naturalScroll = natural)

    @Test fun `the pointer starts in the middle and a slow finger moves it by the unity gain times the precision factor`() {
        val calls = Calls()
        val pointer = sink(calls)
        pointer.onMove(10f, 0f, 0f)
        // 10 mm * (1920 / 160) px/mm * 0.4 = 48 px from 960.
        assertEquals(listOf("cursor 1008,540"), calls.log)
    }

    @Test fun `a faster finger goes further, and the speed setting scales every speed`() {
        val slow = Calls()
        sink(slow).onMove(10f, 0f, 0f)
        val fast = Calls()
        sink(fast).onMove(10f, 0f, 400f)
        val boosted = Calls()
        sink(boosted, speed = 1f).onMove(10f, 0f, 0f)
        fun x(c: Calls) = c.log.single().substringAfter("cursor ").substringBefore(',').toInt()
        assertTrue(x(fast) > x(slow))
        // Speed +1 doubles the gain: 96 px, not 48.
        assertEquals(960 + 96, x(boosted))
    }

    @Test fun `the pointer stays on the screen`() {
        val calls = Calls()
        val pointer = sink(calls)
        pointer.onMove(-10000f, -10000f, 0f)
        pointer.onMove(10000f, 10000f, 0f)
        assertEquals(listOf("cursor 0,0", "cursor 1919,1079"), calls.log)
    }

    @Test fun `nothing happens while the screen's size is not known`() {
        val calls = Calls()
        val pointer = sink(calls, size = null)
        pointer.onMove(5f, 5f, 0f)
        pointer.onButton(EvdevKeys.BTN_LEFT, true)
        pointer.onButton(EvdevKeys.BTN_LEFT, false)
        pointer.onScroll(0f, 50f)
        assertTrue(calls.log.isEmpty())
    }

    @Test fun `a press and release without a move is a tap where it was pressed`() {
        val calls = Calls()
        val pointer = sink(calls)
        pointer.onMove(10f, 0f, 0f)
        calls.log.clear()
        pointer.onButton(EvdevKeys.BTN_LEFT, true)
        assertTrue("a press alone plays nothing", calls.log.isEmpty())
        pointer.onButton(EvdevKeys.BTN_LEFT, false)
        assertEquals(listOf("tap 1008,540"), calls.log)
    }

    @Test fun `a press that moves is a drag from where it was pressed, ending where it is let go`() {
        val calls = Calls()
        val pointer = sink(calls)
        pointer.onButton(EvdevKeys.BTN_LEFT, true)
        pointer.onMove(10f, 0f, 0f)
        pointer.onMove(10f, 0f, 0f)
        pointer.onButton(EvdevKeys.BTN_LEFT, false)
        assertEquals(
            listOf(
                "cursor 1008,540", "dragStart 960,540", "dragTo 1008,540",
                "cursor 1056,540", "dragTo 1056,540",
                "dragEnd 1056,540",
            ),
            calls.log,
        )
    }

    @Test fun `a right press is a long press where the pointer is, a release and the middle button do nothing`() {
        val calls = Calls()
        val pointer = sink(calls)
        pointer.onButton(EvdevKeys.BTN_RIGHT, true)
        pointer.onButton(EvdevKeys.BTN_RIGHT, false)
        pointer.onButton(EvdevKeys.BTN_MIDDLE, true)
        pointer.onButton(EvdevKeys.BTN_MIDDLE, false)
        assertEquals(listOf("context 960,540"), calls.log)
    }

    @Test fun `scrolling gathers finger travel into swipes that follow the fingers when natural`() {
        val calls = Calls()
        val pointer = sink(calls)
        // 1 mm is 12 px: three of them are 36, under a swipe's 40.
        repeat(3) { pointer.onScroll(0f, -1f) }
        assertTrue(calls.log.isEmpty())
        pointer.onScroll(0f, -1f)
        assertEquals(listOf("scroll 960,540 0,-48"), calls.log)
    }

    @Test fun `unnatural scrolling swipes the other way`() {
        val calls = Calls()
        sink(calls, natural = false).onScroll(0f, -5f)
        assertEquals(listOf("scroll 960,540 0,60"), calls.log)
    }

    @Test fun `cancel lets go of a drag, and reset puts the pointer back in the middle`() {
        val calls = Calls()
        val pointer = sink(calls)
        pointer.onButton(EvdevKeys.BTN_LEFT, true)
        pointer.onMove(10f, 0f, 0f)
        calls.log.clear()
        pointer.cancel()
        assertEquals(listOf("dragEnd 1008,540"), calls.log)
        pointer.cancel()
        assertEquals("a second cancel does nothing more", 1, calls.log.size)
        pointer.reset()
        calls.log.clear()
        pointer.onButton(EvdevKeys.BTN_RIGHT, true)
        assertEquals(listOf("context 960,540"), calls.log)
    }

    // ---- the queue

    private class Pending : GestureRunner {
        val ran = mutableListOf<PointerOp>()
        val dones = ArrayDeque<(Boolean) -> Unit>()
        override fun run(op: PointerOp, done: (Boolean) -> Unit) {
            ran += op
            dones.addLast(done)
        }
        fun finish(completed: Boolean = true) = dones.removeFirst().invoke(completed)
    }

    @Test fun `gestures play one at a time, in order`() {
        val runner = Pending()
        val queue = PointerGestureQueue(runner) { _, _ -> }
        queue.tap(1f, 1f)
        queue.contextClick(2f, 2f)
        assertEquals(listOf<PointerOp>(PointerOp.Tap(1f, 1f)), runner.ran)
        runner.finish()
        assertEquals(listOf(PointerOp.Tap(1f, 1f), PointerOp.ContextClick(2f, 2f)), runner.ran)
    }

    @Test fun `a runner that finishes at once does not recurse or reorder`() {
        val ran = mutableListOf<PointerOp>()
        val queue = PointerGestureQueue({ op, done -> ran += op; done(true) }) { _, _ -> }
        queue.tap(1f, 1f)
        queue.tap(2f, 2f)
        queue.dragStart(3f, 3f)
        queue.dragTo(4f, 4f)
        queue.dragEnd(5f, 5f)
        assertEquals(
            listOf(PointerOp.Tap(1f, 1f), PointerOp.Tap(2f, 2f), PointerOp.DragStart(3f, 3f), PointerOp.DragMove(4f, 4f), PointerOp.DragEnd(5f, 5f)),
            ran,
        )
    }

    @Test fun `drag moves waiting behind a gesture collapse to the newest, and the end is never lost`() {
        val runner = Pending()
        val queue = PointerGestureQueue(runner) { _, _ -> }
        queue.dragStart(0f, 0f)
        queue.dragTo(1f, 1f)
        queue.dragTo(2f, 2f)
        queue.dragTo(3f, 3f)
        queue.dragEnd(4f, 4f)
        runner.finish()
        runner.finish()
        assertEquals(listOf(PointerOp.DragStart(0f, 0f), PointerOp.DragEnd(4f, 4f)), runner.ran)
    }

    @Test fun `scrolls waiting behind a gesture add up and stay within a screen's swipe`() {
        val runner = Pending()
        val queue = PointerGestureQueue(runner) { _, _ -> }
        queue.tap(0f, 0f)
        queue.scroll(5f, 5f, 0f, 100f)
        queue.scroll(5f, 5f, 0f, 100f)
        queue.scroll(6f, 6f, 0f, 5000f)
        runner.finish()
        assertEquals(PointerOp.Scroll(6f, 6f, 0f, 900f), runner.ran.last())
    }

    @Test fun `a drag that was interrupted drops the rest of itself and the next gesture still plays`() {
        val runner = Pending()
        val queue = PointerGestureQueue(runner) { _, _ -> }
        queue.dragStart(0f, 0f)
        queue.dragTo(1f, 1f)
        runner.finish(completed = true) // the start
        queue.dragTo(2f, 2f)
        queue.dragEnd(3f, 3f)
        queue.tap(9f, 9f)
        runner.finish(completed = false) // the first move was cancelled: another finger touched the screen
        assertEquals(
            listOf<PointerOp>(PointerOp.DragStart(0f, 0f), PointerOp.DragMove(1f, 1f), PointerOp.Tap(9f, 9f)),
            runner.ran,
        )
    }

    @Test fun `a drag move with no drag open plays nothing, and the cursor is never queued`() {
        val runner = Pending()
        val cursor = mutableListOf<Pair<Float, Float>>()
        val queue = PointerGestureQueue(runner) { x, y -> cursor += x to y }
        queue.dragTo(1f, 1f)
        queue.dragEnd(2f, 2f)
        queue.moveCursor(7f, 8f)
        assertTrue(runner.ran.isEmpty())
        assertEquals(listOf(7f to 8f), cursor)
    }

    // ---- routing

    @Test fun `the shell's own selection wins on the screen it is drawn on, else a pointer, else nothing`() {
        assertEquals(TrackpadRoute.FOCUS, TrackpadRoute.of(shellOnTarget = true, pointerReachable = true))
        assertEquals(TrackpadRoute.FOCUS, TrackpadRoute.of(shellOnTarget = true, pointerReachable = false))
        assertEquals(TrackpadRoute.POINTER, TrackpadRoute.of(shellOnTarget = false, pointerReachable = true))
        assertEquals(TrackpadRoute.UNAVAILABLE, TrackpadRoute.of(shellOnTarget = false, pointerReachable = false))
    }

    private class Tagged(val name: String, val log: MutableList<String>) : TrackpadOutput {
        override fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float) { log += "$name move" }
        override fun onScroll(dxMm: Float, dyMm: Float) { log += "$name scroll" }
        override fun onButton(evdevButton: Int, pressed: Boolean) { log += "$name button $evdevButton $pressed" }
    }

    @Test fun `a gesture that holds the left button stays on the output it started on`() {
        val log = mutableListOf<String>()
        val a = Tagged("a", log)
        val b = Tagged("b", log)
        var current: TrackpadOutput? = a
        val routed = RoutedTrackpadOutput { current }
        routed.onButton(EvdevKeys.BTN_LEFT, true)
        current = b // the shell came to the front mid-drag
        routed.onMove(1f, 1f, 1f)
        routed.onButton(EvdevKeys.BTN_LEFT, false)
        routed.onMove(1f, 1f, 1f)
        assertEquals(
            listOf("a button ${EvdevKeys.BTN_LEFT} true", "a move", "a button ${EvdevKeys.BTN_LEFT} false", "b move"),
            log,
        )
    }

    @Test fun `with nowhere to send, the trackpad does nothing`() {
        val routed = RoutedTrackpadOutput { null }
        routed.onMove(1f, 1f, 1f)
        routed.onScroll(1f, 1f)
        routed.onButton(EvdevKeys.BTN_LEFT, true)
        routed.onButton(EvdevKeys.BTN_LEFT, false)
    }
}

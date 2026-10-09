package dev.droidtop.shell.gamepad.input

/**
 * Keeps a key that follows a press the screen has just acted on from reaching that screen before the change the
 * press made has happened (docs/SPEC.md 6e, "2b", Droidtop/tracker#359).
 *
 * A press that opens a menu only sets state; the menu's window composes, joins the overlay stack
 * ([OverlayKeys]) and gets focus frames later. Two keys sent back to back (a script's "Start, Down" in one adb
 * call) are both dispatched before any of that, so the second landed on the screen under the menu. After a press
 * that was handled ([opens]: a fresh press, not a repeat or a release), later keys are held and delivered,
 * in order, once [nextFrame] has run: by then the overlay is on the stack and [deliver] (the gate's routing)
 * swallows what belongs to the layer beneath. Held keys are delivered, never dropped here; dropping is the
 * stack's decision. Pure over [nextFrame], so a test steps the frames by hand.
 */
internal class KeySettle<E>(
    private val deliver: (E) -> Boolean,
    private val opens: (E) -> Boolean,
    private val nextFrame: (() -> Unit) -> Unit,
) {
    private val held = ArrayDeque<E>()
    private var waiting = false

    /** Moves on with every [clear], so a frame that was waited for before it does not end a later wait. */
    private var generation = 0

    /** Delivers [event] now, or holds it behind a press that is still taking effect (then true: it was taken). */
    fun dispatch(event: E): Boolean {
        if (waiting || held.isNotEmpty()) {
            held.addLast(event)
            return true
        }
        return deliverNow(event)
    }

    /** Forgets the held keys (the window lost the pad). */
    fun clear() {
        held.clear()
        waiting = false
        generation++
    }

    private fun deliverNow(event: E): Boolean {
        val handled = deliver(event)
        if (handled && opens(event)) {
            waiting = true
            val mine = generation
            nextFrame {
                if (mine == generation) {
                    waiting = false
                    drain()
                }
            }
        }
        return handled
    }

    private fun drain() {
        while (!waiting && held.isNotEmpty()) deliverNow(held.removeFirst())
    }
}

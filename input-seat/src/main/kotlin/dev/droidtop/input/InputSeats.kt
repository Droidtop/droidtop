package dev.droidtop.input

import dev.droidtop.hostbridge.HostBridgeInput

/**
 * The one [InputSeat] for a given live bridge.
 *
 * Before this, `DesktopShell` created its seat inside its own composition.
 * That was correct while the desktop surface was the only thing driving
 * input; it stops being correct the moment a second surface on another
 * display -- the trackpad and keyboard of docs/SPEC.md sections 4 and 6 --
 * also needs to reach the same container. Two `InputSeat` instances over
 * one bridge would not break the Wayland protocol (both speak to the same
 * virtual pointer), but it would break the invariant the seat exists to
 * hold: that there is one place where every source is normalized, and one
 * place that knows what is currently held down.
 *
 * A single-entry cache rather than a map, because there is exactly one
 * primary container session at a time (`DesktopSessionService` holds one
 * `Connected` state). A new bridge replaces the old entry, which is also
 * how a session that dropped and came back gets a clean seat rather than
 * one still remembering the previous connection's held buttons.
 */
object InputSeats {

    private var cachedBridge: HostBridgeInput? = null
    private var cachedSeat: InputSeat? = null

    @Synchronized
    fun of(bridge: HostBridgeInput): InputSeat {
        val existing = cachedSeat
        if (existing != null && cachedBridge === bridge) return existing
        val seat = InputSeat(bridge)
        cachedBridge = bridge
        cachedSeat = seat
        return seat
    }

    /** The current seat without creating one -- for surfaces that must stay inert with no session. */
    @Synchronized
    fun current(): InputSeat? = cachedSeat

    /** Called when the session ends, so a stale seat cannot be handed out afterwards. */
    @Synchronized
    fun clear() {
        cachedBridge = null
        cachedSeat = null
    }
}

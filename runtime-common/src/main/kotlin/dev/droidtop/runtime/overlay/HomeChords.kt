package dev.droidtop.runtime.overlay

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One of the overlays droidtop draws over whatever is on screen without backgrounding it (docs/SPEC.md "In-game
 * overlays", Droidtop/tracker#740), on [displayId], the display in front. [frontPackage] is the app whose window was
 * in front when the chord was pressed (droidtop's own included), for the overlays that depend on it; null when unknown.
 * The input lane raises it; the UI lane draws it, and a second request of the same [kind] while it is open closes it.
 */
data class OverlayRequest(val kind: Kind, val displayId: Int, val frontPackage: String? = null) {
    enum class Kind {
        /** HOME + R2. */
        QUICK_MENU,

        /** HOME + L2: what L2 Options shows for the current game, drawn as the companion draws it. */
        CONTEXT_MENU,

        /** HOME + Start: the left menu's launcher content. */
        LEFT_MENU,

        /**
         * Android's accessibility button, the touch route to the three above (Droidtop/tracker#839): a small sheet
         * that picks one of them, or the mode switcher.
         */
        PICKER,
    }
}

/** Between the accessibility key filter, which raises [requests], and the overlay, which collects them. */
object OverlayRequests {
    private val flow = MutableSharedFlow<OverlayRequest>(extraBufferCapacity = 4)

    /** One value per chord; with nobody collecting, the request is dropped. */
    val requests: SharedFlow<OverlayRequest> = flow.asSharedFlow()

    fun raise(request: OverlayRequest): Boolean = flow.tryEmit(request)

    /**
     * True while an overlay is drawn. Its own window coming to the front is not what the person was looking at, so the
     * key filter keeps the app in front from before it.
     */
    @Volatile
    var open: Boolean = false

    /**
     * Set by the overlay while it is open, null otherwise: the pad's keys go here and none reach the app. Called on
     * the main thread.
     */
    @Volatile
    var keys: ((KeyEvent) -> Unit)? = null
}

/**
 * The HOME chords (Droidtop/tracker#740), as a state machine over key codes. Pure: [onKey] says what to do with each
 * key; the accessibility service does it.
 *
 * - HOME down is held. R2, L2 or Start pressed while it is held opens that overlay; droidtop eats HOME and the button,
 *   the button through its release even if HOME is let go first.
 * - HOME let go with no chord is HOME, unchanged: [Decision.home] asks the service to press it, since a key the
 *   service ate cannot go on as the key itself.
 * - While an overlay is open, the pad's presses go to it ([Decision.toOverlay]), each with its own release.
 * - Every other key goes on at once, untouched.
 *
 * A release can be lost (another accessibility service that filters keys ate it, the filter went off mid-press), and
 * nothing may stay stuck for it (Droidtop/tracker#740: "it's possible to bug it so it stops working (maybe inputting
 * too fast)"). So a key pressed again ends whatever its lost release left behind; a HOME held with no HOME event for
 * [STALE_HOLD_MS] counts as let go; and [reset] forgets everything when the filter goes off. Every sequence of
 * presses and releases leaves it idle once each key's last event was a release ([idle], HomeChordsTest).
 */
class HomeChords(private val homeKeys: Set<Int> = setOf(KeyEvent.KEYCODE_HOME)) {
    data class Decision(
        val consume: Boolean,
        val open: OverlayRequest.Kind? = null,
        val home: Boolean = false,
        val toOverlay: Boolean = false,
    )

    private var holding = false
    private var chorded = false
    /** When HOME was last pressed or repeated, by the events' own clock. */
    private var homeAt = 0L

    /** Chord buttons pressed under HOME: theirs to the release. */
    private val eaten = mutableSetOf<Int>()

    /** Pad keys whose press went to an open overlay: their release goes there too, or nowhere if it has closed. */
    private val routed = mutableSetOf<Int>()

    /** Nothing held, nothing owed a release: where every sequence must come back to. */
    val idle: Boolean get() = !holding && !chorded && eaten.isEmpty() && routed.isEmpty()

    /** Forgets every key: the filter went off, so no release of a key it saw will come. */
    fun reset() {
        holding = false
        chorded = false
        eaten.clear()
        routed.clear()
    }

    /** What to do with one key event; [eventTime] is the event's own time (`KeyEvent.getEventTime`). */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, fromPad: Boolean, overlayOpen: Boolean, eventTime: Long = 0L): Decision {
        // A HOME held this long with no HOME event at all lost its release: it is let go, not left holding every chord.
        if (holding && keyCode !in homeKeys && eventTime - homeAt > STALE_HOLD_MS) {
            holding = false
            chorded = false
        }
        if (keyCode in homeKeys) {
            if (down) {
                if (repeatCount == 0) {
                    holding = true
                    chorded = false
                }
                if (holding) homeAt = eventTime
                return if (holding) EAT else PASS
            }
            // A release whose press droidtop did not hold (the filter came on mid-press) is Android's.
            if (!holding) return PASS
            val alone = !chorded
            holding = false
            chorded = false
            return Decision(consume = true, home = alone)
        }
        if (!down) {
            if (eaten.remove(keyCode)) return EAT
            if (routed.remove(keyCode)) return Decision(consume = true, toOverlay = overlayOpen)
        } else if (repeatCount == 0) {
            // Pressed again: its last release never came, so nothing is still owed to that press.
            eaten.remove(keyCode)
            routed.remove(keyCode)
        }
        if (down && holding) {
            kindOf(keyCode)?.let { kind ->
                chorded = true
                eaten += keyCode
                return Decision(consume = true, open = if (repeatCount == 0) kind else null)
            }
        }
        if (down && fromPad && overlayOpen) {
            routed += keyCode
            return Decision(consume = true, toOverlay = true)
        }
        return PASS
    }

    companion object {
        /** How long HOME may be held with no HOME event before it counts as let go. */
        const val STALE_HOLD_MS = 5_000L

        private val PASS = Decision(consume = false)
        private val EAT = Decision(consume = true)

        fun kindOf(keyCode: Int): OverlayRequest.Kind? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R2 -> OverlayRequest.Kind.QUICK_MENU
            KeyEvent.KEYCODE_BUTTON_L2 -> OverlayRequest.Kind.CONTEXT_MENU
            KeyEvent.KEYCODE_BUTTON_START -> OverlayRequest.Kind.LEFT_MENU
            else -> null
        }
    }
}

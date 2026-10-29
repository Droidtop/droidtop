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

    /** Chord buttons pressed under HOME: theirs to the release. */
    private val eaten = mutableSetOf<Int>()

    /** Pad keys whose press went to an open overlay: their release goes there too, or nowhere if it has closed. */
    private val routed = mutableSetOf<Int>()

    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, fromPad: Boolean, overlayOpen: Boolean): Decision {
        if (keyCode in homeKeys) {
            if (down) {
                if (repeatCount == 0) {
                    holding = true
                    chorded = false
                }
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

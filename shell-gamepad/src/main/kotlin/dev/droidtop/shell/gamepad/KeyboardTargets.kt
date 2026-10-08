package dev.droidtop.shell.gamepad

import dev.droidtop.runtime.keyboard.AddonKeyboard
import dev.droidtop.runtime.keyboard.AddonKeyboardRules
import dev.droidtop.runtime.keyboard.KeyboardPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.pocketworkstation.pckeyboard.KeyboardSink

/**
 * Where droidtop's keyboard for a field goes (docs/SPEC.md 4c, "Typing on the add-on display"; Displays, "Keyboard
 * displays on"). Every surface that would draw droidtop's keyboard for a field on a screen without Android's keyboard
 * asks [open] first: the in-window keyboard, a field's own keyboard, and both overlays over other apps. The answer is
 * [Opened.Here] (draw it yourself), [Opened.Nowhere] (droidtop does not control the keyboard) or [Opened.Elsewhere]:
 * the companion's input controller ([companion], which the companion follows) or the internal screen
 * ([internalSurface], installed by `:app`), typing into the asker's own sink. One request per target; the newest
 * wins. Main thread.
 */
object KeyboardTargets {
    /** A field on [fieldDisplay] wants keys in [sink], shown on [target]; [onHide] runs when the target's Hide ends it. */
    class Request(val fieldDisplay: Int, val target: KeyboardPlacement, val sink: KeyboardSink, val onHide: () -> Unit)

    sealed interface Opened {
        data class Elsewhere(val request: Request) : Opened

        data object Here : Opened

        data object Nowhere : Opened
    }

    /** The internal-screen keyboard, drawn by `:app`. */
    interface InternalSurface {
        fun available(): Boolean

        fun show(request: Request): Boolean

        fun hide(request: Request)
    }

    @Volatile
    var internalSurface: InternalSurface? = null

    private val companions = MutableStateFlow<Map<Any, () -> Int?>>(emptyMap())
    private val companionRequest = MutableStateFlow<Request?>(null)

    /** The request the companion's input controller is serving, or null. */
    val companion: StateFlow<Request?> = companionRequest

    /** Changes whenever a companion host starts or stops. */
    val companionsChanged: StateFlow<Map<Any, () -> Int?>> = companions

    /** A companion host [token] is started on the display [display] reads; null while it is stopped. */
    fun companionShown(token: Any, display: (() -> Int?)?) {
        companions.value = if (display == null) companions.value - token else companions.value + (token to display)
    }

    private fun companionDisplays(): Set<Int> = companions.value.values.mapNotNull { it() }.toSet()

    /** Where the keyboard for a field on [fieldDisplay] goes right now. */
    fun placement(fieldDisplay: Int): KeyboardPlacement = AddonKeyboardRules.keyboardPlacement(
        AddonKeyboard.placement.value,
        fieldDisplay,
        companionDisplays(),
        AddonKeyboard.companionHostsKeyboard.value,
        internalSurface?.available() == true,
    )

    fun open(fieldDisplay: Int, sink: KeyboardSink, onHide: () -> Unit = {}): Opened =
        when (val target = placement(fieldDisplay)) {
            KeyboardPlacement.NOT_CONTROLLED -> Opened.Nowhere
            KeyboardPlacement.SAME_SCREEN -> Opened.Here
            KeyboardPlacement.COMPANION -> {
                val request = Request(fieldDisplay, target, sink, onHide)
                companionRequest.value = request
                Opened.Elsewhere(request)
            }
            KeyboardPlacement.INTERNAL_SCREEN -> {
                val request = Request(fieldDisplay, target, sink, onHide)
                if (internalSurface?.show(request) == true) Opened.Elsewhere(request) else Opened.Here
            }
        }

    /** The field lost focus or went away: close its keyboard if it is still the one showing. */
    fun close(request: Request) {
        when (request.target) {
            KeyboardPlacement.COMPANION -> companionRequest.compareAndSet(request, null)
            KeyboardPlacement.INTERNAL_SCREEN -> internalSurface?.hide(request)
            else -> Unit
        }
    }

    /** The companion's Hide. */
    fun hideCompanion() {
        val request = companionRequest.value ?: return
        companionRequest.value = null
        request.onHide()
    }
}

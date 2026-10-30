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

    /** Whether droidtop's keyboard, not Android's, serves a droidtop field on [fieldDisplay] right now (Droidtop/tracker#612). */
    fun servesField(fieldDisplay: Int?, assistive: Boolean = false): Boolean =
        fieldDisplay != null &&
            AddonKeyboardRules.droidtopServesField(fieldDisplay, AddonKeyboard.localDisplays.value, placement(fieldDisplay, assistive))

    /**
     * Whether a keyboard comes up when droidtop itself puts the cursor in a field: only with one screen and no
     * controller or keyboard attached (Droidtop/tracker#612). Otherwise it waits for the person to choose to type.
     */
    fun raisesOnOwnFocus(): Boolean =
        AddonKeyboardRules.raisesOnOwnFocus(companionDisplays().isNotEmpty(), dev.droidtop.shell.gamepad.input.ControllerLayouts.padOrKeyboard.value)

    /** Where the keyboard for a field on [fieldDisplay] goes right now. */
    fun placement(fieldDisplay: Int, assistive: Boolean = false): KeyboardPlacement = AddonKeyboardRules.keyboardPlacement(
        AddonKeyboard.placement.value,
        fieldDisplay,
        companionDisplays(),
        AddonKeyboard.companionHostsKeyboard.value,
        internalSurface?.available() == true,
        assistive,
    )

    /**
     * Whether an assistive service that drives the system keyboard is on (TalkBack or any touch exploration, Switch
     * Access, Select to Speak): then the system keyboard types (AddonKeyboardRules.keyboardPlacement). droidtop's own
     * accessibility service (the taskbar) is not one.
     */
    fun assistiveOn(context: android.content.Context): Boolean {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        if (manager.isTouchExplorationEnabled) return true
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> ASSISTIVE.any { it in info.id.lowercase() } }
    }

    private val ASSISTIVE = listOf("talkback", "switchaccess", "selecttospeak")

    fun open(fieldDisplay: Int, sink: KeyboardSink, onHide: () -> Unit = {}, assistive: Boolean = false): Opened =
        when (val target = placement(fieldDisplay, assistive)) {
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

    /**
     * One keyboard only (Droidtop/tracker#523): a field whose keys are served elsewhere ([Opened.Elsewhere]: the
     * companion's Input controller, the internal screen) must not also bring up Android's keyboard over its own
     * window, which covers what is being edited. The field's own focus asks Android for it after droidtop opened
     * the other, so this runs while the request stands and sends the keyboard away again; the field keeps its focus
     * and its keys. [view] is any view of the field's window.
     */
    fun hideAndroidKeyboard(view: android.view.View) {
        val showing = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (!showing) return
        view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * The companion's docked slab, while it shows (owner, 2026-10-16: the keyboard is "PART of the companion"): it
     * takes the pad's keys, so the pad on the screen with the field moves across and presses the slab on the other.
     * Registered by the companion; null while none shows.
     */
    @Volatile
    var companionPad: ((android.view.KeyEvent) -> Boolean)? = null

    /**
     * A pad key from any droidtop window ([PadGate]): the companion's docked slab gets it while a field's keys are
     * typed there; true when it took it. A keyboard's keys (its arrows, Enter) still go to the field.
     */
    fun routePad(event: android.view.KeyEvent): Boolean {
        if (companionRequest.value == null) return false
        val handler = companionPad ?: return false
        if (!isPadKey(event)) return false
        return handler(event)
    }

    fun isPadKey(event: android.view.KeyEvent): Boolean {
        val fromPad = event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD) || event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)
        return android.view.KeyEvent.isGamepadButton(event.keyCode) || (fromPad && event.keyCode in PAD_DPAD)
    }

    private val PAD_DPAD = setOf(
        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    )

    /** The companion's Hide. */
    fun hideCompanion() {
        val request = companionRequest.value ?: return
        companionRequest.value = null
        request.onHide()
    }
}

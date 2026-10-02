package dev.droidtop.shell.gamepad.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.droidtop.library.controller.CaptureStore
import dev.droidtop.library.controller.ConsoleDef
import dev.droidtop.library.controller.ControllerClassifier
import dev.droidtop.library.controller.FaceLayout
import dev.droidtop.library.controller.HardwareDatabase
import dev.droidtop.library.controller.LayoutResolver
import dev.droidtop.library.controller.LayoutSignals
import dev.droidtop.library.controller.PadCapture
import dev.droidtop.library.controller.PadFacts
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What droidtop knows about the pad in the person's hands: the ONE resolver
 * the key map ([GamepadKeyMap]) and every hint pill read (docs/SPEC.md 7b,
 * "Console and controller detection"). It replaces the one-time "are the
 * face buttons Nintendo-style" answer, which went stale the moment a
 * handheld's own layout toggle was flipped.
 *
 * The ACTIVE pad is the last gamepad that produced input ([noteInput], from
 * the window's key gate); before any input it is the console's built-in pad
 * when this device is in the console table, else the first attached pad.
 * [layout] is Compose snapshot state: a pill that reads it redraws when the
 * answer changes, and the key map swaps in the same instant.
 *
 * [recheck] is the whole of staying current and is cheap enough for every
 * screen change, menu, dialog, resume and window-focus regain: an in-memory
 * property read on the calling thread, and anything heavier (the privileged
 * read, the `getprop` snapshot) on IO, throttled, with the result cached.
 * All state is touched on the main thread only.
 */
object ControllerLayouts {
    private const val TAG = "droidtop.ControllerLayout"
    private const val KEY_CAPTURES = "droidtop_pad_captures"
    private const val KEY_SNAPSHOT = "droidtop_pad_property_snapshot"
    private const val HEAVY_MIN_INTERVAL_MS = 1500L

    private var layoutState by mutableStateOf(FaceLayout.DEFAULT)
    private var recaptureState by mutableStateOf(false)
    private var activeNameState by mutableStateOf<String?>(null)
    private var readyState by mutableStateOf(false)
    private var captureRequestedState by mutableStateOf(false)

    /** The layout every pill and the key map follow right now. */
    val layout: FaceLayout get() = layoutState

    /** The active pad's capture no longer holds and nothing else says what it is: ask lightly, once. */
    val needsRecapture: Boolean get() = recaptureState && !recaptureDismissed

    /** The console table and the saved captures are loaded: until then [layout] is the default, not an answer. */
    val ready: Boolean get() = readyState

    /** The person asked to set the buttons (Quick Menu, onboarding): the shell shows the capture prompt. */
    val captureRequested: Boolean get() = captureRequestedState

    /** Name of the pad the layout is for, for the screens that say so. */
    val activePadName: String? get() = activeNameState

    private data class ActivePad(val id: Int, val descriptor: String, val name: String, val vendorId: Int, val productId: Int)

    // Lazy: the Android Looper and the Main dispatcher do not exist in a JVM unit test, which only reads layouts.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private var loaded = false
    private var loading = false
    private var console: ConsoleDef? = null
    private var captures: Map<String, PadCapture> = emptyMap()
    private var snapshot: Map<String, String> = emptyMap()
    private var signature = ""
    private var toggleValue: String? = null
    private var privilegedToggleValue: String? = null
    private var active: ActivePad? = null
    private var recaptureDismissed by mutableStateOf(false)
    private var heavyRunning = false
    private var lastHeavyAt = 0L
    private var listening = false

    /** Registers for device changes and starts the first load. Call from the shell and from onboarding. */
    fun attach(context: Context) {
        val app = context.applicationContext
        if (!listening) {
            listening = true
            val manager = app.getSystemService(Context.INPUT_SERVICE) as InputManager
            manager.registerInputDeviceListener(
                object : InputManager.InputDeviceListener {
                    override fun onInputDeviceAdded(deviceId: Int) = recompute()

                    override fun onInputDeviceRemoved(deviceId: Int) {
                        if (active?.id == deviceId) active = null
                        recompute()
                    }

                    override fun onInputDeviceChanged(deviceId: Int) = deviceChanged(app, deviceId)
                },
                handler,
            )
        }
        recheck(app)
    }

    /**
     * Re-reads what can change. Safe to call on every screen change: the
     * toggle's value is read in memory right here and the layout recomputed,
     * and the slower reads are throttled to one at a time off the main thread.
     */
    fun recheck(context: Context) {
        val app = context.applicationContext
        if (!loaded) {
            load(app)
            return
        }
        readToggle(app)
        recompute()
        scheduleHeavy(app)
    }

    /** From the window's key gate: a gamepad key going down makes its device the active pad. */
    fun noteInput(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        if ((event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return
        if (active?.id == event.deviceId) return
        val device = event.device ?: return
        active = device.toActivePad()
        recompute()
    }

    /**
     * The person pressed the button labelled A on the active pad
     * ([keyCode] is what arrived). Recorded for THIS pad against the
     * property values as they are now, so a later change to either
     * invalidates it.
     */
    fun capture(context: Context, keyCode: Int) {
        val pad = active ?: firstAttachedPad() ?: return
        active = pad
        record(context.applicationContext, pad, PadCapture(keyCode == KeyEvent.KEYCODE_BUTTON_B, signature, stale = false))
    }

    fun requestCapture() {
        captureRequestedState = true
    }

    fun endCaptureRequest() {
        captureRequestedState = false
    }

    /**
     * The key handler of every "press the button labelled A" surface (the
     * Quick Menu prompt, the light re-confirm, onboarding's Controller
     * step): an A or B pad button is the answer, and both its edges are
     * consumed so nothing else acts on it. Returns whether [event] was
     * taken; [onCaptured] runs once per answer.
     */
    fun handleCaptureKey(context: Context, event: KeyEvent, onCaptured: () -> Unit): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_BUTTON_A && keyCode != KeyEvent.KEYCODE_BUTTON_B) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            noteInput(event)
            capture(context, keyCode)
            onCaptured()
        }
        return true
    }

    /** The one-press escape hatch: A and B (and X and Y) trade meaning on the active pad, whatever was detected. */
    fun swapNow(context: Context) {
        val pad = active ?: firstAttachedPad() ?: return
        active = pad
        record(context.applicationContext, pad, PadCapture(!layout.swapped, signature, stale = false))
    }

    /** "Not now" on the light re-confirm: stay quiet until something changes again. */
    fun dismissRecapture() {
        recaptureDismissed = true
    }

    /** Unit tests set the layout directly; nothing else does. */
    internal fun useLayoutForTest(layout: FaceLayout) {
        layoutState = layout
    }

    /** True when the layout is established, so onboarding's Controller step has been answered. */
    fun isAnswered(): Boolean = layout.known

    /** Every attached gamepad by the name the device reports; the ONE gamepad-detection rule in droidtop. */
    fun attachedControllers(): List<AttachedController> =
        InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val device = InputDevice.getDevice(id) ?: return@mapNotNull null
            if (!device.isGamepad()) return@mapNotNull null
            AttachedController(id = id, name = device.name.trim().ifEmpty { "Controller" })
        }.distinctBy { it.name }

    private fun InputDevice.isGamepad(): Boolean {
        if (isVirtual) return false
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun InputDevice.toActivePad() = ActivePad(
        id = id,
        descriptor = descriptor,
        name = name.trim().ifEmpty { "Controller" },
        vendorId = vendorId,
        productId = productId,
    )

    private fun firstAttachedPad(): ActivePad? =
        InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { it.isGamepad() }
            ?.toActivePad()

    /** The first load reads the console table and the saved captures: files, so off the main thread. */
    private fun load(app: Context) {
        if (loading) return
        loading = true
        scope.launch {
            val loadedState = withContext(Dispatchers.IO) {
                val prefs = app.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
                Triple(
                    HardwareDatabase.forThisDevice(app),
                    CaptureStore.parse(prefs.getString(KEY_CAPTURES, null)),
                    parseSnapshot(prefs.getString(KEY_SNAPSHOT, null)),
                )
            }
            console = loadedState.first
            captures = loadedState.second
            snapshot = loadedState.third
            signature = LayoutSignals.signature(snapshot)
            loaded = true
            loading = false
            readyState = true
            readToggle(app)
            recompute()
            scheduleHeavy(app, force = true)
        }
    }

    private fun readToggle(app: Context) {
        val property = console?.toggle?.property ?: return
        val inProcess = LayoutSignals.readInProcess(property)
        toggleValue = inProcess ?: privilegedToggleValue
        // SELinux can hide the property from the app: ask the privileged helper, off the main thread.
        if (inProcess == null) scheduleHeavy(app, force = false)
    }

    /**
     * The slow reads, one at a time and at most every [HEAVY_MIN_INTERVAL_MS]:
     * the toggle through the privileged helper when the app cannot see it,
     * and the snapshot of layout-looking properties that invalidates a
     * capture when one of them changes. Skipped when the console table or
     * the pad's family already says everything and nothing was captured.
     */
    private fun scheduleHeavy(app: Context, force: Boolean = false) {
        val needsSnapshot = captures.isNotEmpty() || !layout.known
        val needsPrivileged = console?.toggle != null && toggleValue == null
        if (!force && !needsSnapshot && !needsPrivileged) return
        val now = SystemClock.elapsedRealtime()
        if (heavyRunning || (!force && now - lastHeavyAt < HEAVY_MIN_INTERVAL_MS)) return
        heavyRunning = true
        lastHeavyAt = now
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val property = console?.toggle?.property
                val privileged = if (needsPrivileged && property != null) LayoutSignals.readPrivileged(property) else null
                val fresh = if (needsSnapshot || force) LayoutSignals.snapshot() else null
                privileged to fresh
            }
            heavyRunning = false
            result.first?.let { privilegedToggleValue = it }
            result.second?.let { fresh ->
                if (fresh != snapshot) {
                    val changed = LayoutSignals.diff(snapshot, fresh)
                    if (snapshot.isNotEmpty() && changed.isNotEmpty()) {
                        // So a toggle that turns out to matter can be added to the console table.
                        Log.i(TAG, "layout-looking properties changed: ${changed.joinToString("; ")}")
                    }
                    snapshot = fresh
                    signature = LayoutSignals.signature(fresh)
                    recaptureDismissed = false
                    app.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_SNAPSHOT, formatSnapshot(fresh)).apply()
                }
            }
            if (toggleValue == null) toggleValue = privilegedToggleValue
            recompute()
        }
    }

    /** The system said the device itself changed (key layout, remap): its capture can no longer be trusted. */
    private fun deviceChanged(app: Context, deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        val descriptor = device.descriptor
        val capture = captures[descriptor]
        if (capture != null && !capture.stale) {
            record(app, descriptor, capture.copy(stale = true), activate = false)
            recaptureDismissed = false
        }
        recompute()
    }

    private fun record(app: Context, pad: ActivePad, capture: PadCapture) {
        recaptureDismissed = false
        record(app, pad.descriptor, capture, activate = true)
    }

    private fun record(app: Context, descriptor: String, capture: PadCapture, activate: Boolean) {
        captures = captures + (descriptor to capture)
        app.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CAPTURES, CaptureStore.format(captures)).apply()
        if (activate) recompute()
    }

    private fun recompute() {
        val pad = active ?: firstAttachedPad()
        val def = console
        val builtIn = def != null && (pad == null || def.pad?.matches(pad.name, pad.vendorId, pad.productId) == true)
        val facts = PadFacts(
            console = def,
            builtIn = builtIn,
            externalFamily = pad?.let { ControllerClassifier.classify(it.vendorId, it.productId, it.name) },
            toggleValue = toggleValue,
            capture = pad?.let { captures[it.descriptor] },
            signature = signature,
        )
        val resolved = LayoutResolver.resolve(facts)
        // Equal values do not invalidate a snapshot state, so a repeat check redraws nothing.
        layoutState = resolved
        recaptureState = LayoutResolver.needsRecapture(facts, resolved)
        activeNameState = pad?.name
    }

    private fun parseSnapshot(text: String?): Map<String, String> =
        text?.lineSequence()?.mapNotNull { line ->
            val at = line.indexOf('\t')
            if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
        }?.toMap()?.toSortedMap() ?: emptyMap()

    private fun formatSnapshot(snapshot: Map<String, String>): String =
        snapshot.entries.joinToString("\n") { it.key + "\t" + it.value }
}

/** One attached pad: the id Android knows it by, and the name it reports. */
data class AttachedController(val id: Int, val name: String)

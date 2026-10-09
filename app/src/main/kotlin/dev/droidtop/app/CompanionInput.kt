package dev.droidtop.app

import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import dev.droidtop.input.TrackpadConfig
import dev.droidtop.library.settings.TrackpadSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.pocketworkstation.pckeyboard.Macro
import org.pocketworkstation.pckeyboard.MacroStep

/**
 * The companion's Input tab beyond the keyboard grid and the trackpad (docs/SPEC.md "The companion's tabs", Input;
 * Droidtop/tracker#414 slice C12): where the keys go and how the header names it, the trackpad's settings as the
 * gesture engine takes them, the chord keys, and the controllers' batteries. Pure parts here; the view is
 * [SecondScreenInputView].
 */
internal object InputTarget {
    /** One screen as Input names it: Android's id, its unique id (what a pin is stored as) and droidtop's name for it. */
    data class Screen(val id: Int, val uniqueId: String?, val name: String)

    /**
     * The screen keys go to outside Desktop: the pinned one while it is connected, else the screen the shell is drawn
     * on, else the first other screen. Never the companion's own ([own]): touching the companion never moves the
     * target. Pure.
     */
    fun displayId(own: Int?, shell: Int?, screens: List<Screen>, pinned: String?): Int? {
        if (pinned != null) screens.firstOrNull { it.uniqueId == pinned && it.id != own }?.let { return it.id }
        if (shell != null && shell != own) return shell
        return screens.firstOrNull { it.id != own }?.id
    }

    /**
     * What the header says: in Desktop the keys and the pointer go to the Linux desktop (the container's input seat,
     * whichever screen shows it); elsewhere the screen [displayId] picks, "(pinned)" when the person pinned it.
     */
    fun label(desktop: Boolean, target: Screen?, pinned: Boolean): String = when {
        desktop -> "Typing to: Linux desktop"
        target == null -> "Typing to: no other screen"
        pinned -> "Typing to: ${target.name} (pinned)"
        else -> "Typing to: ${target.name}"
    }
}

/** The trackpad's settings (Companion group > Trackpad) as the gesture engine takes them. Pure. */
internal fun trackpadConfig(settings: TrackpadSettings): TrackpadConfig = TrackpadConfig(
    tapTimeoutMs = settings.tapMs.toLong(),
    tapDragTimeoutMs = settings.dragWindowMs.toLong(),
    tapToClick = settings.tapToClick,
    twoFingerScroll = settings.twoFingerScroll,
    twoFingerRightClick = settings.twoFingerRightClick,
    threeFingerMiddleClick = settings.threeFingerMiddleClick,
    dragLock = settings.dragLock,
    momentum = settings.momentum,
)

/** libinput's pointer speed (-1..1) from the setting's tenths. */
internal fun trackpadSpeed(settings: TrackpadSettings): Float = settings.speed.coerceIn(-10, 10) / 10f

/**
 * The chord keys above the keyboard: what a handheld's touch keyboard makes awkward, each played by the vendored
 * keyboard's own [org.pocketworkstation.pckeyboard.MacroPlayer] into the one keyboard destination, so they go where
 * typed keys go: the container's input seat in Desktop (the compositor gets Alt+Tab and Super, Android never sees them
 * as shortcuts), else the focused app.
 */
internal object CompanionChords {
    val ALL: List<Macro> = listOf(
        Macro("Esc", listOf(MacroStep.Chord(KeyEvent.KEYCODE_ESCAPE))),
        Macro("Tab", listOf(MacroStep.Chord(KeyEvent.KEYCODE_TAB))),
        Macro("Alt+Tab", listOf(MacroStep.Chord(KeyEvent.KEYCODE_TAB, alt = true))),
        Macro("Super", listOf(MacroStep.Chord(KeyEvent.KEYCODE_META_LEFT))),
        Macro("Ctrl+C", listOf(MacroStep.Chord(KeyEvent.KEYCODE_C, ctrl = true))),
        Macro("Ctrl+V", listOf(MacroStep.Chord(KeyEvent.KEYCODE_V, ctrl = true))),
        Macro("Ctrl+Z", listOf(MacroStep.Chord(KeyEvent.KEYCODE_Z, ctrl = true))),
    )
}

/** Input's "Tabs" button asks the companion to show its bar over Input (the handle does the same). */
internal object CompanionInputHandle {
    val showTabs = MutableStateFlow(false)
}

/**
 * The connected controllers and their batteries (`InputDevice.getBatteryState`, Android 12 and later; before that no
 * battery is reported). Read when Input or Home shows, never polled.
 */
internal object Controllers {
    data class Pad(val name: String, val percent: Int?)

    fun read(context: Context): List<Pad> {
        val manager = context.getSystemService(android.hardware.input.InputManager::class.java) ?: return emptyList()
        return manager.inputDeviceIds.toList().mapNotNull { id ->
            val device = manager.getInputDevice(id) ?: return@mapNotNull null
            val pad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (!pad || device.isVirtual) return@mapNotNull null
            Pad(device.name, batteryPercent(device))
        }.distinctBy { it.name }
    }

    private fun batteryPercent(device: InputDevice): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val battery = device.batteryState
        if (!battery.isPresent) return null
        val capacity = battery.capacity
        return if (capacity.isNaN()) null else (capacity * 100).toInt().coerceIn(0, 100)
    }

    /** Input's controllers line: each pad with its battery, or none. Pure. */
    fun line(pads: List<Pad>): String? = pads.takeIf { it.isNotEmpty() }?.joinToString(", ") { pad ->
        pad.name + (pad.percent?.let { " $it%" } ?: "")
    }

    /** Home's status line: the controller in hand, when exactly one reports a battery ("Pad 80%"). Pure. */
    fun status(pads: List<Pad>): String? = pads.filter { it.percent != null }.singleOrNull()?.let { "Pad ${it.percent}%" }
}

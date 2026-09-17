package dev.droidtop.shell.gamepad.input

import android.content.Context
import android.view.InputDevice
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * What droidtop knows about the pad in the person's hands, and the one
 * thing it asks them about it.
 *
 * The question is the face-button layout. Android reports a pad's buttons
 * by POSITION -- `KEYCODE_BUTTON_A` is the bottom face button whatever is
 * printed on it -- so a Nintendo-style pad, where the bottom button is
 * labelled B, confirms with a button whose label says cancel. No amount
 * of detection can answer that, because the answer is what is printed on
 * the plastic and what the person expects, so droidtop asks once instead
 * of guessing (docs/SPEC.md 7b).
 *
 * Same SharedPreferences file as every other droidtop preference, and the
 * same shape as [InputMapPrefs] beside it.
 */
object ControllerPrefs {
    private const val KEY_SWAP = "droidtop_gamepad_swap_confirm_cancel"
    private const val KEY_ASKED = "droidtop_gamepad_layout_asked"

    fun swapConfirmCancel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SWAP, false)

    /** Records the answer AND that it was answered, which are different facts. */
    fun setSwapConfirmCancel(context: Context, swapped: Boolean) {
        prefs(context).edit().putBoolean(KEY_SWAP, swapped).putBoolean(KEY_ASKED, true).apply()
        GamepadKeyMap.load(context)
    }

    /** Whether the person has been asked. A skipped step is not an answer. */
    fun asked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Every attached game controller, by the name the device reports.
     *
     * The ONE gamepad-detection rule in droidtop: a device whose sources
     * include a gamepad or a joystick and that is not the virtual keyboard
     * Android always reports. The Quick Menu's status header asks this
     * same function -- there is not a second detector for onboarding.
     */
    fun attachedControllers(): List<AttachedController> =
        InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val device = InputDevice.getDevice(id) ?: return@mapNotNull null
            if (device.isVirtual) return@mapNotNull null
            val sources = device.sources
            val isPad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isPad) return@mapNotNull null
            AttachedController(id = id, name = device.name.trim().ifEmpty { "Controller" })
        }.distinctBy { it.name }
}

/** One attached pad: the id Android knows it by, and the name it reports. */
data class AttachedController(val id: Int, val name: String)

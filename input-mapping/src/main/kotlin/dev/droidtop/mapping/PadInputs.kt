package dev.droidtop.mapping

/**
 * A pad's buttons by Steam's names (Droidtop/tracker#618 C3): the source and input a [MappingEngine] layout binds,
 * for an Android key code. Pure; the codes are Android's `KeyEvent.KEYCODE_*` values, written out so this module keeps
 * no Android types. A Nintendo-layout face swap is the host's to apply before it asks.
 */
object PadInputs {
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_X = 99
    const val KEYCODE_BUTTON_Y = 100
    const val KEYCODE_BUTTON_L1 = 102
    const val KEYCODE_BUTTON_R1 = 103
    const val KEYCODE_BUTTON_L2 = 104
    const val KEYCODE_BUTTON_R2 = 105
    const val KEYCODE_BUTTON_THUMBL = 106
    const val KEYCODE_BUTTON_THUMBR = 107
    const val KEYCODE_BUTTON_START = 108
    const val KEYCODE_BUTTON_SELECT = 109

    /** The physical input [keyCode] is, or null for a key that is not a pad button. */
    fun physical(keyCode: Int): MappingEngine.Physical? = when (keyCode) {
        KEYCODE_BUTTON_A -> MappingEngine.Physical("button_diamond", "button_a")
        KEYCODE_BUTTON_B -> MappingEngine.Physical("button_diamond", "button_b")
        KEYCODE_BUTTON_X -> MappingEngine.Physical("button_diamond", "button_x")
        KEYCODE_BUTTON_Y -> MappingEngine.Physical("button_diamond", "button_y")
        KEYCODE_DPAD_UP -> MappingEngine.Physical("dpad", "dpad_north")
        KEYCODE_DPAD_DOWN -> MappingEngine.Physical("dpad", "dpad_south")
        KEYCODE_DPAD_LEFT -> MappingEngine.Physical("dpad", "dpad_west")
        KEYCODE_DPAD_RIGHT -> MappingEngine.Physical("dpad", "dpad_east")
        KEYCODE_BUTTON_L1 -> MappingEngine.Physical("switch", "left_bumper")
        KEYCODE_BUTTON_R1 -> MappingEngine.Physical("switch", "right_bumper")
        KEYCODE_BUTTON_START -> MappingEngine.Physical("switch", "button_menu")
        KEYCODE_BUTTON_SELECT -> MappingEngine.Physical("switch", "button_escape")
        KEYCODE_BUTTON_L2 -> MappingEngine.Physical("left_trigger", "click")
        KEYCODE_BUTTON_R2 -> MappingEngine.Physical("right_trigger", "click")
        KEYCODE_BUTTON_THUMBL -> MappingEngine.Physical("joystick", "click")
        KEYCODE_BUTTON_THUMBR -> MappingEngine.Physical("right_joystick", "click")
        else -> null
    }

    /** The source of the left stick and of the right one, for [MappingEngine.stick]. */
    const val LEFT_STICK = "joystick"
    const val RIGHT_STICK = "right_joystick"
}

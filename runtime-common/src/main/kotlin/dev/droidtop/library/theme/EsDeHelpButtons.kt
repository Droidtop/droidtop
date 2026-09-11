package dev.droidtop.library.theme

/**
 * Which controller family a `helpsystem`'s button art is chosen from --
 * real ES-DE's `InputControllerType` setting, whose own accepted values
 * these are (GuiMenu.cpp:1374-1392) and whose real default is `xbox`
 * (Settings.cpp:246). It is a SETTING there, not a probe of the attached
 * pad, and it is one here too: a theme ships one art set per family and
 * the person picks which one they want to look at.
 *
 * `xbox` being the default is also the honest answer for the console this
 * runs on, whose pad reports the Xbox ABXY layout.
 */
enum class EsDeControllerFamily(val id: String) {
    XBOX("xbox"),
    XBOX360("xbox360"),
    PS4("ps4"),
    PS5("ps5"),
    SNES("snes"),
    SWITCH_PRO("switchpro"),
    ;

    companion object {
        fun of(id: String?): EsDeControllerFamily =
            entries.firstOrNull { it.id == id } ?: XBOX
    }
}

/**
 * One help-bar entry's real `customButtonIcon` art key, as ES-DE's own
 * `assignIcons` (HelpComponent.cpp:435-560) builds it.
 *
 * Two things about that function decide the shape here:
 *
 *  * Twelve of the keys are family-INDEPENDENT (HelpComponent.cpp:453-480,
 *    under its own comment "These graphics files are common between all
 *    controller types") -- the d-pad set, the thumbstick click and the six
 *    shoulder/trigger keys.
 *  * The face buttons and back/start are per family, and back/start split
 *    further WITHIN the PlayStation and Xbox families (`button_back_PS123`
 *    / `_PS4` / `_PS5`, `button_back_XBOX` / `_XBOX360`), which is why the
 *    family enum has six members for four art sets.
 *
 * Returns null for an action ES-DE has no help icon for at all, which is
 * then drawn as droidtop's own text label -- the same outcome as a theme
 * that simply shipped no art for this button.
 */
fun esDeHelpButtonIconKey(button: EsDeHelpButton, family: EsDeControllerFamily): String? {
    val faceSuffix = when (family) {
        EsDeControllerFamily.SNES -> "SNES"
        EsDeControllerFamily.SWITCH_PRO -> "switch"
        EsDeControllerFamily.PS4, EsDeControllerFamily.PS5 -> "PS"
        EsDeControllerFamily.XBOX, EsDeControllerFamily.XBOX360 -> "XBOX"
    }
    // HelpComponent.cpp:496-560: back/start are the one place where the
    // PlayStation and Xbox families name more than one art set each.
    val edgeSuffix = when (family) {
        EsDeControllerFamily.SNES -> "SNES"
        EsDeControllerFamily.SWITCH_PRO -> "switch"
        EsDeControllerFamily.PS4 -> "PS4"
        EsDeControllerFamily.PS5 -> "PS5"
        EsDeControllerFamily.XBOX -> "XBOX"
        EsDeControllerFamily.XBOX360 -> "XBOX360"
    }
    return when (button) {
        EsDeHelpButton.A -> "button_a_$faceSuffix"
        EsDeHelpButton.B -> "button_b_$faceSuffix"
        EsDeHelpButton.X -> "button_x_$faceSuffix"
        EsDeHelpButton.Y -> "button_y_$faceSuffix"
        EsDeHelpButton.BACK -> "button_back_$edgeSuffix"
        EsDeHelpButton.START -> "button_start_$edgeSuffix"
        EsDeHelpButton.DPAD_UP -> "dpad_up"
        EsDeHelpButton.DPAD_DOWN -> "dpad_down"
        EsDeHelpButton.DPAD_UPDOWN -> "dpad_updown"
        EsDeHelpButton.DPAD_LEFTRIGHT -> "dpad_leftright"
        EsDeHelpButton.DPAD_ALL -> "dpad_all"
        EsDeHelpButton.THUMBSTICK_CLICK -> "thumbstick_click"
        EsDeHelpButton.SHOULDER_L -> "button_l"
        EsDeHelpButton.SHOULDER_R -> "button_r"
        EsDeHelpButton.SHOULDER_LR -> "button_lr"
        EsDeHelpButton.TRIGGER_L -> "button_lt"
        EsDeHelpButton.TRIGGER_R -> "button_rt"
        EsDeHelpButton.TRIGGER_LR -> "button_ltrt"
    }
}

/** The buttons ES-DE's own help bar has art for; see [esDeHelpButtonIconKey]. */
enum class EsDeHelpButton {
    A, B, X, Y, BACK, START,
    DPAD_UP, DPAD_DOWN, DPAD_UPDOWN, DPAD_LEFTRIGHT, DPAD_ALL,
    THUMBSTICK_CLICK,
    SHOULDER_L, SHOULDER_R, SHOULDER_LR,
    TRIGGER_L, TRIGGER_R, TRIGGER_LR,
}

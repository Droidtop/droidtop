package dev.droidtop.library.theme

/**
 * Real ES-DE badge slot types -- `BadgeComponent.cpp`'s own real
 * `mBadgeTypes` list (`SLOT_*` defines), same order. `collection`/
 * `folder` have no real droidtop data to drive them yet (no custom-
 * collections/folder-entry concept in droidtop's data model -- same
 * tracked gap `GameMetadataEntity`'s own doc comment notes for
 * `collectionSortName`) -- `EsDeThemedBadges` never marks either active
 * regardless of what a theme's `<slots>` requests, an honest gap, not a
 * silently-dropped one.
 */
val BADGE_SLOTS = listOf(
    "collection", "folder", "favorite", "completed", "kidgame",
    "broken", "controller", "altemulator", "manual",
)

/**
 * Real ES-DE controller badge/metadata list -- ported unchanged from
 * `BadgeComponent.cpp`'s own real `sControllerDefinitions` table (a real
 * local clone kept at /root/es-de-reference for ongoing reference), same
 * order (the "unknown" entry stays last -- [EsDeController.byShortName]'s
 * own real fallback-to-last behavior, matching `BadgeComponent::
 * getShortName`, depends on it). Deliberately does NOT point at real
 * ES-DE's own bundled Qt-resource SVGs -- droidtop has no access to
 * those assets; `EsDeThemedBadges`' own controller-overlay rendering
 * falls back to a plain unicode glyph instead (see that function's own
 * doc comment), same real licensing/IP reason `EsDeThemedBadges`'s
 * favorite-slot rendering already documents.
 */
data class EsDeController(val shortName: String, val displayName: String)

object EsDeControllers {
    val all: List<EsDeController> = listOf(
        EsDeController("gamepad_generic", "Gamepad (Generic)"),
        EsDeController("gamepad_nintendo_nes", "Gamepad (Nintendo NES)"),
        EsDeController("gamepad_nintendo_snes", "Gamepad (Nintendo SNES)"),
        EsDeController("gamepad_nintendo_64", "Gamepad (Nintendo 64)"),
        EsDeController("gamepad_nintendo_gamecube", "Gamepad (Nintendo GameCube)"),
        EsDeController("gamepad_playstation", "Gamepad (PlayStation)"),
        EsDeController("gamepad_sega_master_system", "Gamepad (Sega Master System)"),
        EsDeController("gamepad_sega_md_3_buttons", "Gamepad (Sega Mega Drive/Genesis 3 Buttons)"),
        EsDeController("gamepad_sega_md_6_buttons", "Gamepad (Sega Mega Drive/Genesis 6 Buttons)"),
        EsDeController("gamepad_sega_dreamcast", "Gamepad (Sega Dreamcast)"),
        EsDeController("gamepad_xbox", "Gamepad (Xbox)"),
        EsDeController("joystick_generic", "Joystick (Generic)"),
        EsDeController("joystick_arcade_no_buttons", "Joystick (Arcade No Buttons)"),
        EsDeController("joystick_arcade_no_buttons_twin", "Joystick (Arcade No Buttons Twin Stick)"),
        EsDeController("joystick_arcade_1_button", "Joystick (Arcade 1 Button)"),
        EsDeController("joystick_arcade_2_buttons", "Joystick (Arcade 2 Buttons)"),
        EsDeController("joystick_arcade_3_buttons", "Joystick (Arcade 3 Buttons)"),
        EsDeController("joystick_arcade_4_buttons", "Joystick (Arcade 4 Buttons)"),
        EsDeController("joystick_arcade_5_buttons", "Joystick (Arcade 5 Buttons)"),
        EsDeController("joystick_arcade_6_buttons", "Joystick (Arcade 6 Buttons)"),
        EsDeController("keyboard_generic", "Keyboard (Generic)"),
        EsDeController("keyboard_and_mouse_generic", "Keyboard and Mouse (Generic)"),
        EsDeController("mouse_generic", "Mouse (Generic)"),
        EsDeController("mouse_amiga", "Mouse (Amiga)"),
        EsDeController("lightgun_generic", "Lightgun (Generic)"),
        EsDeController("lightgun_nintendo", "Lightgun (Nintendo)"),
        EsDeController("steering_wheel_generic", "Steering Wheel (Generic)"),
        EsDeController("flight_stick_generic", "Flight Stick (Generic)"),
        EsDeController("spinner_generic", "Spinner (Generic)"),
        EsDeController("trackball_generic", "Trackball (Generic)"),
        EsDeController("wii_remote_nintendo", "Wii Remote (Nintendo)"),
        EsDeController("wii_remote_and_nunchuk_nintendo", "Wii Remote and Nunchuk (Nintendo)"),
        EsDeController("joycon_left_or_right_nintendo", "Joy-Con Left or Right (Nintendo)"),
        EsDeController("joycon_pair_nintendo", "Joy-Con Pair (Nintendo)"),
        EsDeController("xbox_kinect", "Xbox Kinect"),
        EsDeController("unknown", "Unknown Controller"),
    )

    /** Real ES-DE fallback: an unrecognized shortName resolves to the last (unknown) entry. */
    fun byShortName(shortName: String?): EsDeController =
        all.firstOrNull { it.shortName == shortName } ?: all.last()
}

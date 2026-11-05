package dev.droidtop.library.settings

import dev.droidtop.runtime.R
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.droidtop.library.theme.EsDeAspectRatio
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeDownloader
import dev.droidtop.library.theme.ThemePrefs
import dev.droidtop.runtime.systemstatus.PowerAction
import dev.droidtop.runtime.tasks.text

/**
 * The Gaming mode settings catalog -- the single definition of what
 * Gaming's settings ARE (see SettingsCatalog.kt for the model and the
 * renderer contract). Layout convention shared by every mode's catalog
 * (docs/SPEC.md settings architecture): the droidtop-wide "global" group
 * first (a renderer whose chrome already exposes global settings -- the
 * unified SettingsActivity's persistent action-bar item -- skips it by
 * group id), then the mode's own settings, then shortcuts to the OTHER
 * modes' settings last, so nobody ever has to switch modes just to reach
 * a setting.
 */
object GamingSettingsCatalog {

    const val GROUP_GLOBAL = "global"
    /**
     * The shell's own group. It used to be the ONLY group besides System:
     * sixteen unrelated rows in one undifferentiated column, with the
     * section label component already written and nothing using it
     * (research/ui-polish item 16). The rows are the same rows in the
     * same order; they are now under labels that say what they are for.
     */
    const val GROUP_GAMING = "gaming"
    const val GROUP_LIBRARY = "gaming_library"
    const val GROUP_APPEARANCE = "gaming_appearance"
    const val GROUP_SCREENS = "gaming_screens"
    const val GROUP_INPUT = "gaming_input"
    const val GROUP_ACCOUNTS = "gaming_accounts"
    const val GROUP_ANDROID = "gaming_android"

    /**
     * The settings category column, top to bottom (docs/SPEC.md "Settings layout"): the catalog's own
     * groups and the groups of the screens merged into it (Global settings) name these as their
     * `category`, and the column sorts by this list, so where a group sits in the catalog does not
     * decide where its category sits.
     */
    val CATEGORY_ORDER = listOf(
        // Reorganised 2026-10-23 into short categories, one home per setting (Droidtop/tracker#610, the owner:
        // "Settings would be very wise to reconfigure, yes").
        // Nine since Droidtop/tracker#621 M8 (owner, 2026-10-23: "let's do all nine of those proposals"): Kids and
        // guests is a section of Modes, as who the device is handed to is a mode, and Accessibility one of Appearance.
        "Modes", "Library", "Accounts and sources", "Appearance",
        "Controls and keyboard", "Displays", "Companion screen", "Shell behaviour", "System",
    )
    const val GROUP_KIDS = "gaming_kids"
    const val GROUP_KEYBOARD = "gaming_keyboard"
    const val GROUP_COMPANION_SCREEN = "gaming_companion_screen"
    const val ID_CONTROLLER = "pref_gaming_controller"
    const val ID_HOME_CHORDS = "pref_gaming_home_chords"
    const val GROUP_OTHER_SHELLS = "other_shells"

    const val ID_GLOBAL_SETTINGS = "pref_global_settings"
    const val ID_DEFAULT_SECTION = "pref_gaming_default_section"
    const val ID_SHOW_HINTS = "pref_gaming_show_hints"
    const val ID_ANIMATIONS = "pref_gaming_animations"
    /** The switch Navigation sounds was before it was a volume: read once, so a person who turned it off stays at 0. */
    private const val ID_NAVIGATION_SOUNDS_SWITCH = "pref_gaming_navigation_sounds"
    const val ID_NAVIGATION_SOUNDS = "pref_gaming_navigation_sounds_volume"
    const val ID_SCRAPER = "pref_gaming_scraper"
    const val ID_ACCOUNTS_AND_SOURCES = "pref_gaming_accounts_and_sources"
    const val ID_SCREENSAVER = "pref_gaming_screensaver"
    const val ID_SCREENSAVER_PAGE = "pref_gaming_screensaver_page"
    const val ID_SCREENSAVER_CONTENT = "pref_gaming_screensaver_content"
    const val ID_SCREENSAVER_DIM = "pref_gaming_screensaver_dim"
    const val ID_SCREENSAVER_NAME = "pref_gaming_screensaver_name"
    const val ID_GAME_AWAKE = "pref_gaming_game_awake"
    const val ID_UI_MODE = "pref_gaming_ui_mode"
    const val ID_CONSOLE_SYSTEMS = "pref_gaming_console_systems"
    const val ID_EMULATORS = "pref_gaming_emulators"
    const val ID_WINDOWS_GAMES = "pref_gaming_windows_games"
    const val GROUP_SYSTEM = "gaming_system"
    const val ID_SYSTEM_NETWORK = "pref_gaming_system_network"
    const val ID_SYSTEM_VOLUME = "pref_gaming_system_volume"
    const val ID_SYSTEM_BRIGHTNESS = "pref_gaming_system_brightness"
    const val ID_COMPANION_WAKE = "pref_companion_screen_wake"
    const val ID_SCREENSHOT = "pref_screenshot"
    const val ID_TYPE_FROM_DEVICE = "pref_type_from_device"
    const val ID_STOP_FOREGROUND = "pref_stop_foreground_app"
    const val ID_SYSTEM_BRIGHTNESS_GRANT = "pref_gaming_system_brightness_grant"
    const val ID_SYSTEM_BLUETOOTH = "pref_gaming_system_bluetooth"
    const val ID_SYSTEM_VPN = "pref_gaming_system_vpn"
    const val ID_SYSTEM_BATTERY = "pref_gaming_system_battery"
    const val ID_SYSTEM_LEAVE_UI_MODE = "pref_gaming_system_leave_ui_mode"
    const val ID_SYSTEM_SWITCH_MODE = "pref_gaming_system_switch_mode"
    const val ID_SYSTEM_DND = "pref_gaming_system_dnd"
    const val ID_SYSTEM_DND_GRANT = "pref_gaming_system_dnd_grant"
    const val ID_SYSTEM_ADAPTIVE = "pref_gaming_system_adaptive"
    const val ID_SYSTEM_ROTATE = "pref_gaming_system_rotate"
    const val ID_SYSTEM_TIMEOUT = "pref_gaming_system_timeout"
    const val ID_SYSTEM_ANDROID_LINKS = "pref_gaming_system_android_links"
    const val ID_SYSTEM_AIRPLANE = "pref_gaming_system_airplane"
    const val ID_SYSTEM_POWER_MENU = "pref_gaming_system_power_menu"
    const val ID_SYSTEM_SLEEP = "pref_gaming_system_sleep"
    const val ID_SYSTEM_POWER_OFF = "pref_gaming_system_power_off"
    const val ID_SYSTEM_RESTART = "pref_gaming_system_restart"
    const val ID_AUDIO_OUTPUT = "pref_gaming_audio_output"
    const val ID_ORIENTATION = "pref_screen_orientation_gaming"

    /**
     * The Quick Menu's and the companion's Display "Screen orientation": the mode droidtop is in now, where
     * [ID_ORIENTATION] (Gaming's own settings page) is always Gaming's (Droidtop/tracker#571 row 34: the shared row
     * edited Gaming's orientation from Standard and Desktop too).
     */
    const val ID_ORIENTATION_HERE = "pref_screen_orientation_current"
    const val ID_PERFORMANCE_MODE = "pref_gaming_performance_mode"
    const val ID_SYSTEM_MIC_MUTE = "pref_gaming_system_mic_mute"
    const val ID_SYSTEM_FLASHLIGHT = "pref_gaming_system_flashlight"
    const val ID_SYSTEM_LOCATION = "pref_gaming_system_location"
    const val ID_SYSTEM_WIFI = "pref_gaming_system_wifi"
    const val ID_PRIVACY_DASHBOARD = "action_privacy_dashboard"

    /**
     * Screen orientation for the mode droidtop is in now ([Modes.lastMode]), stored under that mode's own key
     * (`pref_screen_orientation_<mode>`, the key ScreenOrientationPrefs applies; Gaming has no portrait). Quick
     * Menu and companion only ([ID_ORIENTATION_HERE]).
     */
    private fun currentModeOrientationItem(context: Context): ChoiceItem {
        val mode = Mode.byId(Modes.lastMode(context)) ?: Mode.GAMING
        val key = "pref_screen_orientation_" + mode.id
        val options = buildList {
            add(ChoiceOption("follow", "Follow device"))
            if (mode != Mode.GAMING) {
                add(ChoiceOption("portrait", "Portrait"))
                add(ChoiceOption("portrait_flipped", "Portrait (flipped)"))
            }
            add(ChoiceOption("landscape", "Landscape"))
            add(ChoiceOption("landscape_flipped", "Landscape (flipped)"))
        }
        return ChoiceItem(
            id = ID_ORIENTATION_HERE,
            title = words(R.string.gaming_screen_orientation),
            subtitle = words(R.string.gaming_in_x_mode, mode.label),
            options = options,
            current = CatalogPrefs.prefs(context).getString(key, "follow"),
            onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(key, value).apply() },
        )
    }

    private val TIMEOUT_OPTIONS = dev.droidtop.runtime.systemstatus.SystemControls.SCREEN_TIMEOUTS
    const val ID_DOWNLOADS = "pref_gaming_downloads"
    const val ID_GAME_FOLDERS = "pref_gaming_game_folders"

    /** A screen's role row ([screenRoleItems]): this prefix and the screen's stable id. */
    const val ID_SCREEN_ROLE_PREFIX = "screen_role:"
    const val ID_DISPLAY_GAME_LAUNCH_TARGET = "pref_display_game_launch_target"
    const val ID_DISPLAY_SWAP = "action_display_swap"
    const val ID_DISPLAY_REINIT = "action_display_reinit"
    const val ID_DISPLAY_KEYBOARD = "action_display_keyboard"
    const val ID_DISPLAY_KEYBOARD_ACCESSIBILITY = "action_display_keyboard_accessibility"
    const val ID_DISPLAY_KEYBOARD_PLACEMENT = "pref_display_keyboard_placement"
    const val ID_DISPLAY_COMPANION = "screen_display_companion"
    const val ID_DISPLAY_KEYBOARD_RESTRICTED = "action_display_keyboard_restricted"
    const val ID_KEYBOARD_PICK = "action_keyboard_pick"
    const val ID_KEYBOARD_ENABLE = "action_keyboard_enable"
    const val ID_RESCAN_LIBRARY = "pref_gaming_rescan_library"
    const val ID_THEME = "pref_gaming_theme"
    const val ID_THEME_COLOR_SCHEME = "pref_gaming_theme_colorscheme"
    /** The colour scheme choice that follows Light or dark rather than naming a scheme (stored as no choice). */
    const val SCHEME_AUTOMATIC = "automatic"
    const val ID_THEME_VARIANT = "pref_gaming_theme_variant"
    const val ID_THEME_ASPECT_RATIO = "pref_gaming_theme_aspect_ratio"
    const val ID_BROWSE_THEMES = "pref_gaming_browse_themes"
    const val ID_APPS_GRID_COLUMNS = "pref_gaming_apps_grid_columns"
    const val ID_ICON_PACK = "pref_gaming_icon_pack"
    const val ID_DESKTOP_SETTINGS = "pref_gaming_desktop_settings"
    const val ID_STANDARD_SETTINGS = "pref_gaming_standard_settings"

    const val MIN_APPS_GRID_COLUMNS = 2
    const val MAX_APPS_GRID_COLUMNS = 10
    const val DEFAULT_APPS_GRID_COLUMNS = 5

    /**
     * What a Settings renderer shows: the catalog without its quick-only
     * groups (docs/SPEC.md 7f, "Where things live"). The Quick Menu reads
     * [groups] whole.
     */
    fun settingsGroups(context: Context): List<CatalogGroup> = groups(context).filterNot { it.quickOnly }

    /**
     * Builds the live catalog. Values are read fresh on every call --
     * renderers rebuild after applying a change (cheap: a few pref reads
     * plus the active theme's already-cached capabilities).
     */
    fun groups(context: Context): List<CatalogGroup> = listOf(
        CatalogGroup(
            id = GROUP_GLOBAL,
            title = null,
            items = listOf(
                NestedScreenItem(
                    id = ID_GLOBAL_SETTINGS,
                    title = words(R.string.gaming_settings),
                    subtitle = words(R.string.gaming_home_screen_and_modes),
                    registryId = "global_settings",
                    icon = CatalogIcon.GLOBAL,
                ),
            ),
        ),
        CatalogGroup(
            id = GROUP_GAMING,
            title = words(R.string.gaming_shell),
            icon = CatalogIcon.GAMING,
            category = "Shell behaviour",
            items = buildList {
                add(defaultSectionItem(context))
                add(
                    NestedScreenItem(
                        id = "pref_gaming_quick_menu_order",
                        title = words(R.string.gaming_quick_menu_order),
                        subtitle = words(R.string.gaming_which_section_comes_where_on_the),
                        inline = QuickMenuOrder.screen(),
                    ),
                )
                // The person's say over Home's rows (Droidtop/tracker#611): pinned, hidden, and the pinned order.
                add(
                    NestedScreenItem(
                        id = "pref_gaming_home_rows",
                        title = words(R.string.gaming_home_rows),
                        subtitle = words(R.string.gaming_pin_rows_to_the_top_of),
                        inline = HomeRows.screen(),
                    ),
                )
                add(showHintsItem(context))
                add(animationsItem(context))
                add(navigationSoundsItem(context))
                add(screensaverItem(context))
                add(gameAwakeItem(context))
            },
        ),
        // Kid, Kiosk and Guest: who the device is handed to, and the limits that go with it.
        CatalogGroup(
            id = GROUP_KIDS,
            title = words(R.string.gaming_kids_and_guests),
            icon = CatalogIcon.GAMING,
            category = "Modes",
            items = buildList {
                add(
                    ChoiceItem(
                        id = ID_UI_MODE,
                        title = words(R.string.gaming_ui_mode),
                        subtitle = null,
                        options = UiMode.entries.map { ChoiceOption(it.name, it.label) },
                        current = UiModePrefs.get(context).name,
                        onSelect = { ctx, value ->
                            UiModeRefresh.set(ctx, runCatching { UiMode.valueOf(value) }.getOrDefault(UiMode.FULL))
                        },
                    ),
                )
                // The optional passkey for leaving Kid, Kiosk and Guest (UiModePasskey): digits, stored hashed. Its row
                // says whether one is set, never what it is, and how to get out when it is forgotten.
                val passkeySet = UiModePasskey.isSet(context)
                add(
                    TextInputItem(
                        id = ControlAccess.ID_UI_MODE_PASSKEY,
                        title = words(R.string.gaming_passkey_to_leave_kid_kiosk_and),
                        subtitle = (
                            if (passkeySet) {
                                "Set: leaving asks for it. Clear the field to remove it. "
                            } else {
                                "None: leaving takes one press. Enter 4 to 8 digits to ask for them. "
                            }
                            ) + UiModePasskey.RECOVERY,
                        value = "",
                        secret = true,
                        onChange = { ctx, value -> UiModePasskey.set(ctx, value.trim()) },
                    ),
                )
                // Kid's maximum volume, applied in the one volume write path (VolumeControl); off by default.
                dev.droidtop.runtime.systemstatus.VolumeControl.load(context)
                add(
                    ChoiceItem(
                        id = ControlAccess.ID_KID_VOLUME_CAP,
                        title = words(R.string.gaming_kid_mode_s_highest_volume),
                        subtitle = words(R.string.gaming_in_kid_mode_no_slider_or),
                        options = listOf(ChoiceOption("0", "No limit (default)")) + listOf(30, 50, 70).map { ChoiceOption(it.toString(), "$it%") },
                        current = dev.droidtop.runtime.systemstatus.VolumeControl.kidCapPercent.toString(),
                        onSelect = { ctx, value -> dev.droidtop.runtime.systemstatus.VolumeControl.setKidCap(ctx, value.toIntOrNull() ?: 0) },
                    ),
                )
                add(PlayTimeSettings.link())
            },
        ),
        CatalogGroup(
            id = GROUP_LIBRARY,
            title = words(R.string.gaming_library),
            category = "Library",
            items = buildList {
                // The person's own play, by game and system (docs/SPEC.md 7f "Your play").
                add(PlaySummaryScreen.link())
                add(
                    NestedScreenItem(
                        id = ID_SCRAPER,
                        title = words(R.string.gaming_scraper),
                        subtitle = words(R.string.gaming_artwork_and_metadata),
                        registryId = "rom_scraper",
                        icon = CatalogIcon.SCRAPER,
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_CONSOLE_SYSTEMS,
                        title = words(R.string.gaming_console_systems),
                        subtitle = words(R.string.gaming_systems_and_folders),
                        registryId = "console_systems",
                        icon = CatalogIcon.CONSOLE_SYSTEMS,
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_EMULATORS,
                        title = words(R.string.gaming_emulators),
                        subtitle = words(R.string.gaming_system_emulators),
                        registryId = "emulators",
                        icon = CatalogIcon.CONSOLE_SYSTEMS,
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_DOWNLOADS,
                        title = words(R.string.gaming_downloads_and_updates),
                        subtitle = words(R.string.gaming_active_and_recent_and_what_has),
                        registryId = "plugin_jobs",
                        icon = CatalogIcon.INTEGRATIONS,
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_GAME_FOLDERS,
                        title = words(R.string.gaming_game_folders),
                        subtitle = words(R.string.gaming_add_or_remove_the_folders_droidtop),
                        registryId = "rom_folders",
                        icon = CatalogIcon.GAME_FOLDERS,
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_WINDOWS_GAMES,
                        title = words(R.string.gaming_windows_games),
                        // This is the destination launchWindows's own
                        // "isn't set up yet" error names, so the title
                        // there and here must stay in step.
                        subtitle = words(R.string.gaming_set_up_the_wine_environment_windows),
                        registryId = "windows_games",
                        icon = CatalogIcon.WINDOWS_GAMES,
                    ),
                )

                // Rescan library is not a row here: it is a one-shot
                // library action, and lives in the Games section's options
                // menu and on Game folders (the same item, by id).
            },
        ),
        // A group of one link is a hub: its screen is drawn directly in the pane, one level less.
        CatalogGroup(
            id = GROUP_ACCOUNTS,
            title = null,
            items = listOf(
                NestedScreenItem(
                    id = ID_ACCOUNTS_AND_SOURCES,
                    title = words(R.string.gaming_accounts_and_sources),
                    subtitle = words(R.string.gaming_accounts_and_plugins),
                    registryId = "accounts_and_sources",
                    icon = CatalogIcon.INTEGRATIONS,
                ),
            ),
        ),
        // Settings' own System group is configuration only: which screen
        // is which, and the two screens that manage droidtop and the
        // device. The live controls are the quick-only group below.
        CatalogGroup(
            id = GROUP_SCREENS,
            title = words(R.string.gaming_displays),
            icon = CatalogIcon.DISPLAY,
            category = "Displays",
            items = buildList {
                add(ChoiceItem(
                    id = ID_ORIENTATION,
                    title = words(R.string.gaming_screen_orientation),
                    options = listOf(
                        ChoiceOption("follow", "Follow device"),
                        ChoiceOption("landscape", "Landscape"),
                        ChoiceOption("landscape_flipped", "Landscape (flipped)"),
                    ),
                    current = CatalogPrefs.prefs(context).getString(ID_ORIENTATION, "follow"),
                    onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(ID_ORIENTATION, value).apply() },
                ))
                addAll(screenRoleItems(context))
                add(displayGameLaunchTargetItem(context))
                add(interfaceSizeItem(context, dev.droidtop.runtime.DisplayRole.MAIN))
                add(interfaceSizeItem(context, dev.droidtop.runtime.DisplayRole.COMPANION))
                addAll(screenNameItems(context))
            },
        ),
        // Every keyboard setting in one place, with the controls: they were split over Controls, Displays and the
        // Companion screen (Droidtop/tracker#610).
        CatalogGroup(
            id = GROUP_KEYBOARD,
            title = words(R.string.gaming_keyboard_on_the_screens),
            icon = CatalogIcon.INPUT,
            category = "Controls and keyboard",
            items = secondScreenKeyboardItems(context).filter { it.id != ID_DISPLAY_COMPANION },
        ),
        // The companion screen's own settings, a category of their own: they were about 28 rows under Displays. A
        // group of one link is a hub, so its screen is drawn directly in the pane, named by the link.
        CatalogGroup(
            id = GROUP_COMPANION_SCREEN,
            title = null,
            items = secondScreenKeyboardItems(context).filter { it.id == ID_DISPLAY_COMPANION },
        ),
        CatalogGroup(
            id = GROUP_ANDROID,
            title = words(R.string.gaming_android),
            category = "System",
            items = listOf(
                NestedScreenItem(
                    id = ID_SYSTEM_ANDROID_LINKS,
                    title = words(R.string.gaming_android_settings),
                    subtitle = words(R.string.gaming_system_settings),
                    registryId = "android_settings",
                    icon = CatalogIcon.ANDROID_SETTINGS,
                ),
                // Reachable from the main screen too, not only the companion's System card (Droidtop/tracker#826 item 6).
                privacyDashboardItem(),
            ),
        ),
        CatalogGroup(
            id = GROUP_INPUT,
            title = words(R.string.gaming_controls),
            category = "Controls and keyboard",
            icon = CatalogIcon.INPUT,
            items = buildList {
                // Onboarding's own Controller step, re-entered (docs/SPEC.md
                // 7b: every step is re-enterable from the Settings row that
                // owns it). Not a second screen asking the same question.
                add(
                    ActionItem(
                        id = ID_CONTROLLER,
                        title = words(R.string.gaming_controller),
                        subtitle = words(R.string.gaming_controller_setup),
                        run = launchComponent(
                            "dev.droidtop.app.OnboardingActivity",
                            "dev.droidtop.app.EXTRA_START_STEP" to "CONTROLLER",
                        ),
                    ),
                )
                // Whether HOME + R2, L2 and Start work over games, and the one press that makes them (Droidtop/tracker#740):
                // they need droidtop's accessibility service, which nothing else says.
                val (chordsOn, chordsLine) = dev.droidtop.runtime.keyboard.AccessibilityKeyboard.homeChords(context)
                add(
                    AsyncActionItem(
                        id = ID_HOME_CHORDS,
                        title = words(R.string.gaming_home_button_combos_over_games),
                        subtitle = chordsLine,
                        value = if (chordsOn) "On" else "Off",
                        statusMark = if (chordsOn) null else StatusMark.ATTENTION,
                        run = { ctx, _ ->
                            if (chordsOn) {
                                dev.droidtop.runtime.keyboard.AccessibilityKeyboard.openSettings(ctx)
                                "Turn it off in Android's list"
                            } else {
                                dev.droidtop.runtime.keyboard.AccessibilityKeyboard.turnOn(ctx)
                            }
                        },
                    ),
                )
                // Keyboard. droidtop ships Hacker's Keyboard because a
                // device meant to replace a computer needs Ctrl/Alt/Esc/
                // Tab/arrows/function keys, and it cannot silently set the
                // system input method -- that needs WRITE_SECURE_SETTINGS,
                // which a normal app is not granted. So: say why, then
                // open Android's own pickers.
                add(
                    ActionItem(
                        id = ID_KEYBOARD_PICK,
                        title = words(R.string.gaming_keyboard),
                        subtitle = keyboardSubtitle(context),
                        value = Keyboards.enabled(context).firstOrNull { it.isCurrent }?.label,
                        run = { ctx -> Keyboards.showPicker(ctx) },
                    ),
                )
                if (!Keyboards.ownKeyboardEnabled(context)) {
                    add(
                        ActionItem(
                            id = ID_KEYBOARD_ENABLE,
                            title = words(R.string.gaming_turn_on_hacker_s_keyboard),
                            subtitle = Keyboards.WHY,
                            run = { ctx -> Keyboards.openSystemSettings(ctx) },
                        ),
                    )
                }
            },
        ),
        CatalogGroup(
            id = GROUP_APPEARANCE,
            title = words(R.string.gaming_appearance),
            category = "Appearance",
            icon = CatalogIcon.APPEARANCE,
            items = buildList {
                add(themeItem(context))
                themeColorSchemeItem(context)?.let { add(it) }
                themeVariantItem(context)?.let { add(it) }
                themeAspectRatioItem(context)?.let { add(it) }
                add(
                    ActionItem(
                        id = ID_BROWSE_THEMES,
                        title = words(R.string.gaming_browse_themes),
                        // Default fulfillment: deep-link into the shell's
                        // ThemeBrowserScreen. The in-shell renderer opens
                        // the browser inline instead (by id).
                        run = launchComponent(
                            "dev.droidtop.app.MainActivity",
                            "dev.droidtop.app.EXTRA_MODE" to "gaming",
                            "dev.droidtop.app.EXTRA_GAMING_BROWSE_THEMES" to true,
                        ),
                    ),
                )
                add(appsGridColumnsItem(context))
                iconPackItem(context)?.let { add(it) }
            },
        ),
        // Live device state and one-shot device actions: the Quick Menu's
        // System tab draws these, and Settings does not (quickOnly). They
        // used to be listed in both, row for row (UI pass 2026-09-24, M2).
        CatalogGroup(
            id = GROUP_SYSTEM,
            title = words(R.string.gaming_system),
            quickOnly = true,
            items = buildList {
                val status = dev.droidtop.runtime.systemstatus.SystemStatus.snapshot(context)
                val controls = dev.droidtop.runtime.systemstatus.SystemControls
                val network = when (status.network) {
                    dev.droidtop.runtime.systemstatus.NetworkKind.WIFI ->
                        "Wi-Fi" + (status.wifiLevel?.let { ", signal $it/4" } ?: "")
                    dev.droidtop.runtime.systemstatus.NetworkKind.ETHERNET -> "Ethernet"
                    dev.droidtop.runtime.systemstatus.NetworkKind.CELLULAR -> "Mobile data"
                    dev.droidtop.runtime.systemstatus.NetworkKind.NONE -> "Offline"
                }
                val noInternet = status.network != dev.droidtop.runtime.systemstatus.NetworkKind.NONE &&
                    !status.validated
                // The mode switcher, first: the Quick Menu is where a
                // person in Gaming looks for "get me out of here", and the
                // only other route was a long-press of Back that nothing
                // names (rig, dq-coordinator-24, stuck moments).
                add(
                    ActionItem(
                        id = ID_SYSTEM_SWITCH_MODE,
                        title = words(R.string.gaming_switch_mode),
                        subtitle = words(R.string.gaming_home_screen),
                        run = launchComponent("dev.droidtop.app.ModeSwitcherActivity"),
                    ),
                )
                add(
                    ActionItem(
                        id = ID_SYSTEM_NETWORK,
                        // The row's NAME, and nothing else. What the
                        // network is set to is state, and state goes in
                        // the value column like every other row's
                        // (CatalogItem.value) -- the list read
                        // "Network: Wi-Fi, signal 4/4" as a title while
                        // the row beside it put its state in the column
                        // (rig, builds 542 and 546). The Quick Menu drew
                        // it correctly by splitting the title back apart
                        // again, which was a second mechanism for the
                        // same job; the title is written right here now
                        // and that splitter is gone.
                        title = words(R.string.gaming_network),
                        // The captive-portal state, said out loud: the
                        // row's action opens the system sheet where
                        // signing in actually happens.
                        value = if (noInternet) "$network, no internet" else network,
                        // The system's own internet panel -- apps lost
                        // programmatic Wi-Fi toggling in API 29, and
                        // opening the real control beats faking one.
                        subtitle = if (noInternet) {
                            "Connected, but nothing gets through: a sign-in page may be waiting. Opens Wi-Fi and data controls"
                        } else {
                            "Opens Wi-Fi and data controls"
                        },
                        run = { ctx ->
                            SettingsLaunch.start(ctx, controls.internetPanelIntent())
                        },
                    ),
                )
                // Swaps the menus and the companion between their screens and remembers it (ScreenRoles.swap), in the
                // Quick Menu's System tab, reachable from whichever screen the person is looking at.
                add(
                    AsyncActionItem(
                        id = ID_DISPLAY_SWAP,
                        title = words(R.string.gaming_swap_screens),
                        subtitle = words(R.string.gaming_puts_the_menus_on_the_companion),
                        run = { ctx, _ -> dev.droidtop.runtime.ScreenRoles.swap(ctx) },
                    ),
                )
                // A second screen stuck in its low safe mode (docs/SPEC.md
                // section 4) is named here, where the fix is finished.
                val degraded = dev.droidtop.runtime.DisplayOutputRepository(context)
                    .currentOutputsSnapshot()
                    .firstOrNull {
                        it.kind == dev.droidtop.runtime.DisplayOutputKind.SECOND_SCREEN && it.isInFallbackMode
                    }
                add(
                    ActionItem(
                        id = ID_DISPLAY_REINIT,
                        title = words(R.string.gaming_reinitialize_displays),
                        subtitle = if (degraded != null) {
                            "The second screen is in a low-resolution mode: turn it off and on again, then reinitialize"
                        } else {
                            "Detect connected screens again and re-place the shell"
                        },
                        value = degraded?.modeSummary(),
                        run = { _ -> dev.droidtop.runtime.DisplayArrangement.reinitialize() },
                    ),
                )
                // Android's font and display size (slice C22): the Quick Menu's Display section and the companion's
                // Accessibility card draw the same items (AccessibilityControls).
                addAll(
                    dev.droidtop.runtime.systemstatus.AccessibilityControls.items(context).filter {
                        it.id == dev.droidtop.runtime.systemstatus.AccessibilityControls.ID_FONT ||
                            it.id == dev.droidtop.runtime.systemstatus.AccessibilityControls.ID_DISPLAY_SIZE
                    },
                )
                // A screenshot of the main screen, pinnable on the companion's Home (slice C15, Capture).
                add(
                    AsyncActionItem(
                        id = ID_SCREENSHOT,
                        title = words(R.string.gaming_screenshot),
                        subtitle = words(R.string.gaming_saves_a_picture_of_the_main),
                        run = { ctx, _ -> dev.droidtop.runtime.Capture.take(ctx.applicationContext, android.view.Display.DEFAULT_DISPLAY).message },
                    ),
                )
                // Typing from a phone or PC on the same network into the field in focus (Droidtop/tracker#645): the
                // console screen with the QR code, where the code is confirmed.
                add(
                    ActionItem(
                        id = ID_TYPE_FROM_DEVICE,
                        title = words(R.string.gaming_type_from_another_device),
                        subtitle = words(R.string.gaming_scan_a_qr_code_with_a),
                        run = launchComponent("dev.droidtop.app.RemoteInputActivity"),
                    ),
                )
                // Stops the app in front on the main screen (slice C20), only with the helper app: one stop route,
                // TaskManager.close, asking first while Ask before stopping is on.
                if (runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)) {
                    add(
                        AsyncActionItem(
                            id = ID_STOP_FOREGROUND,
                            title = words(R.string.gaming_stop_the_app_in_front),
                            subtitle = words(R.string.gaming_ends_the_app_on_the_main),
                            confirmTitle = if (CompanionPrefs.settings.value.askBeforeStopping) "Stop the app in front? Anything not saved in it is lost." else null,
                            run = { ctx, _ ->
                                val pkg = dev.droidtop.runtime.tasks.AppsInsights.foreground(ctx) ?: return@AsyncActionItem "Nothing in front"
                                when (val outcome = dev.droidtop.runtime.tasks.TaskManager.close(ctx, pkg)) {
                                    dev.droidtop.runtime.tasks.CloseOutcome.Closed -> "Stopped"
                                    else -> outcome.text
                                }
                            },
                        ),
                    )
                }
                // Wakes an off companion screen (slice C14); only while a second screen holds the companion.
                if (!dev.droidtop.runtime.CompanionSheet.offered(context)) {
                    add(
                        ActionItem(
                            id = ID_COMPANION_WAKE,
                            title = words(R.string.gaming_companion_screen),
                            subtitle = words(R.string.gaming_turns_the_companion_screen_back_on),
                            run = { _ -> CompanionPrefs.requestWake() },
                        ),
                    )
                }
                add(
                    SliderItem(
                        id = ID_SYSTEM_VOLUME,
                        title = words(R.string.gaming_volume),
                        min = 0,
                        max = controls.volumeRange(context).last,
                        current = controls.volume(context),
                        onChange = { ctx, value -> controls.setVolume(ctx, value) },
                    ),
                )
                if (controls.canWriteBrightness(context)) {
                    add(
                        SliderItem(
                            id = ID_SYSTEM_BRIGHTNESS,
                            title = words(R.string.gaming_brightness),
                            min = 0,
                            max = 255,
                            current = dev.droidtop.runtime.DisplayControls.brightness(context) ?: 128,
                            onChange = { ctx, value -> dev.droidtop.runtime.DisplayControls.setBrightness(ctx, value) },
                        ),
                    )
                } else {
                    add(
                        ActionItem(
                            id = ID_SYSTEM_BRIGHTNESS_GRANT,
                            // The quick setting this row IS, named as
                            // itself rather than as the permission behind
                            // it; what is missing goes in the value column
                            // like any other state (ui-polish item 15).
                            title = words(R.string.gaming_brightness),
                            subtitle = words(R.string.gaming_opens_the_system_screen_where_droidtop),
                            value = "Needs permission",
                            run = { ctx -> SettingsLaunch.start(ctx, controls.brightnessGrantIntent(ctx)) },
                        ),
                    )
                }
                // The controls Android actually lets an app OWN, owned
                // (per direction: consume the user's UI needs in-app;
                // the Settings app is for linking into, not living in).
                if (controls.hasDndAccess(context)) {
                    add(
                        ToggleItem(
                            id = ID_SYSTEM_DND,
                            title = words(R.string.gaming_do_not_disturb),
                            current = controls.dndEnabled(context),
                            onToggle = { ctx, on -> controls.setDnd(ctx, on) },
                        ),
                    )
                } else {
                    add(
                        ActionItem(
                            id = ID_SYSTEM_DND_GRANT,
                            title = words(R.string.gaming_do_not_disturb),
                            value = "Needs permission",
                            run = { ctx -> SettingsLaunch.start(ctx, controls.dndGrantIntent()) },
                        ),
                    )
                }
                if (controls.canWriteBrightness(context)) {
                    add(
                        ToggleItem(
                            id = ID_SYSTEM_ADAPTIVE,
                            title = words(R.string.gaming_adaptive_brightness),
                            current = controls.adaptiveBrightness(context),
                            onToggle = { ctx, on -> controls.setAdaptiveBrightness(ctx, on) },
                        ),
                    )
                    add(
                        ToggleItem(
                            id = ID_SYSTEM_ROTATE,
                            title = words(R.string.gaming_auto_rotate),
                            current = controls.autoRotate(context),
                            onToggle = { ctx, on -> controls.setAutoRotate(ctx, on) },
                        ),
                    )
                    add(currentModeOrientationItem(context))
                    add(
                        ChoiceItem(
                            id = ID_SYSTEM_TIMEOUT,
                            title = words(R.string.gaming_screen_timeout),
                            // The device's own value is offered too when it is not one of ours, named in words
                            // ("Never" for Android's largest value), never as raw milliseconds.
                            options = (TIMEOUT_OPTIONS.map { it.first } + listOfNotNull(controls.screenTimeoutMs(context)))
                                .distinct()
                                .map { ms -> ChoiceOption(ms.toString(), controls.timeoutLabel(ms)) },
                            current = controls.screenTimeoutMs(context)?.toString(),
                            onSelect = { ctx, value ->
                                value.toIntOrNull()?.let { controls.setScreenTimeoutMs(ctx, it) }
                            },
                        ),
                    )
                }
                // Only while restricted: a row offering to leave a mode
                // nobody is in is noise, and this is the ONE way back
                // once Settings is hidden.
                // With a passkey set it opens the passkey screen (pad or touch) instead of asking yes or no.
                if (UiModePrefs.get(context).hidesSettings) {
                    val passkey = UiModePasskey.isSet(context)
                    add(
                        ActionItem(
                            id = ID_SYSTEM_LEAVE_UI_MODE,
                            title = words(R.string.gaming_leave_x, UiModePrefs.get(context).label),
                            subtitle = words(R.string.gaming_restores_settings_and_the_full_library),
                            confirmTitle = if (passkey) null else "Leave restricted mode?",
                            run = if (passkey) {
                                launchComponent(UiModePasskey.ENTRY_ACTIVITY)
                            } else {
                                { ctx -> UiModeRefresh.set(ctx, UiMode.FULL) }
                            },
                        ),
                    )
                }
                add(
                    ActionItem(
                        id = ID_SYSTEM_VPN,
                        title = words(R.string.gaming_vpn),
                        subtitle = words(R.string.gaming_opens_the_system_vpn_screen_to),
                        state = status.vpnActive,
                        run = { ctx ->
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ),
                )
                // Battery: the live level and Android's own Battery Saver
                // state, with the press opening the system Battery Saver
                // screen. Apps cannot switch power-save mode themselves
                // (PowerManager.setPowerSaveModeEnabled is signature-only),
                // so like Network and Bluetooth this opens the real control
                // rather than faking one (Droidtop/tracker#84).
                add(
                    ActionItem(
                        id = ID_SYSTEM_BATTERY,
                        title = words(R.string.gaming_battery),
                        subtitle = words(R.string.gaming_opens_the_system_battery_saver_screen),
                        value = controls.batteryValue(status.batteryPercent, status.charging, controls.powerSaveOn(context)),
                        run = { ctx -> SettingsLaunch.start(ctx, controls.batterySaverSettingsIntent()) },
                    ),
                )
                // With the helper app (a `priv.shell` provider) Wi-Fi, Bluetooth and airplane mode switch in place through
                // the shell's own command; without it they open Android's own panel (docs/SPEC.md "Privileged actions").
                // Shizuku's binder reaches this process a moment after it starts: wait for it briefly, or the first
                // menu after a start offered only links (tracker#581). Off the main thread, as the catalog is built.
                runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.connect(1_500) }
                val radios = runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
                if (radios) add(radioToggle(context, dev.droidtop.runtime.systemstatus.SystemControls.Radio.WIFI, ID_SYSTEM_WIFI, "Wi-Fi"))
                if (radios) {
                    add(radioToggle(context, dev.droidtop.runtime.systemstatus.SystemControls.Radio.BLUETOOTH, ID_SYSTEM_BLUETOOTH, "Bluetooth"))
                } else {
                    add(
                        ActionItem(
                            id = ID_SYSTEM_BLUETOOTH,
                            title = words(R.string.gaming_bluetooth),
                            subtitle = words(R.string.gaming_pair_controllers_and_audio_in_the),
                            state = controls.bluetoothOn(context),
                            run = { ctx -> SettingsLaunch.start(ctx, controls.bluetoothSettingsIntent()) },
                        ),
                    )
                }
                // Location: Android's own switch; an app can read it but not set it.
                add(
                    ActionItem(
                        id = ID_SYSTEM_LOCATION,
                        title = words(R.string.gaming_location),
                        subtitle = words(R.string.gaming_opens_android_s_location_screen),
                        state = controls.locationOn(context),
                        run = { ctx -> SettingsLaunch.start(ctx, controls.locationSettingsIntent()) },
                    ),
                )
                // Microphone mute for every app at once (AudioManager), what a call's mute button does.
                add(
                    ToggleItem(
                        id = ID_SYSTEM_MIC_MUTE,
                        title = words(R.string.gaming_mute_microphone),
                        subtitle = words(R.string.gaming_every_app_hears_silence_from_the),
                        current = controls.micMuted(context),
                        onToggle = { ctx, on -> controls.setMicMuted(ctx, on) },
                    ),
                )
                // The flashlight, where the device has one: the camera's torch, no permission needed.
                if (controls.hasFlashlight(context)) {
                    add(
                        ToggleItem(
                            id = ID_SYSTEM_FLASHLIGHT,
                            title = words(R.string.gaming_flashlight),
                            subtitle = words(R.string.gaming_the_light_beside_the_camera),
                            current = controls.flashlightOn(),
                            onToggle = { ctx, on -> controls.setFlashlight(ctx, on) },
                        ),
                    )
                }
                // Airplane mode: Android gives an app no write to it (the radios are the system's), so without the
                // helper app the row shows the real state and opens the real screen.
                if (radios) {
                    add(radioToggle(context, dev.droidtop.runtime.systemstatus.SystemControls.Radio.AIRPLANE, ID_SYSTEM_AIRPLANE, "Airplane mode"))
                } else {
                    add(
                        ActionItem(
                            id = ID_SYSTEM_AIRPLANE,
                            title = words(R.string.gaming_airplane_mode),
                            subtitle = words(R.string.gaming_opens_the_system_screen_android_does),
                            state = controls.airplaneModeOn(context),
                            run = { ctx -> SettingsLaunch.start(ctx, controls.airplaneModeSettingsIntent()) },
                        ),
                    )
                }
                // Sleep (docs/SPEC.md 7f, "Sleep and return to game"): with a privilege provider it puts the
                // console to sleep, the game suspended in place; without one the row only says what to press.
                val power = dev.droidtop.runtime.tasks.TaskManager.shell.capabilities().shellCommand
                if (power) {
                    add(
                        AsyncActionItem(
                            id = ID_SYSTEM_SLEEP,
                            title = words(R.string.gaming_sleep),
                            run = { _, _ -> PowerAction.SLEEP.run(dev.droidtop.runtime.tasks.TaskManager.shell) },
                        ),
                    )
                } else {
                    // A press says what to do instead of nothing (Droidtop/tracker#571 row 20).
                    add(
                        AsyncActionItem(
                            id = ID_SYSTEM_SLEEP,
                            title = words(R.string.gaming_sleep),
                            value = "Power button",
                            run = { _, _ -> "Press the power button to sleep. With More controls set up, droidtop can do it from here" },
                        ),
                    )
                }
                // The power menu (shut down, restart) belongs to the system; a plain app has no call that
                // opens it. With a privilege provider (Shizuku or Sui, the `priv.shell` capability) the
                // shell's own long press of the power key does; without one the row is not drawn at all
                // (docs/SPEC.md "Copy: labels and values", privileged controls).
                if (power) {
                    add(
                        AsyncActionItem(
                            id = ID_SYSTEM_POWER_MENU,
                            title = words(R.string.gaming_power_menu),
                            run = { _, _ ->
                                val out = dev.droidtop.runtime.tasks.TaskManager.shell
                                    .exec(listOf("input", "keyevent", "--longpress", "KEYCODE_POWER"))
                                if (out != null && out.exit == 0) "" else "Failed: the helper could not open it"
                            },
                        ),
                    )
                }
                // Power off and Restart: two presses, the first arms the tile (confirmTitle). Needs a provider.
                if (power) {
                    add(
                        AsyncActionItem(
                            id = ID_SYSTEM_POWER_OFF,
                            title = words(R.string.gaming_power_off),
                            confirmTitle = "Power off?",
                            run = { _, _ -> PowerAction.POWER_OFF.run(dev.droidtop.runtime.tasks.TaskManager.shell) },
                        ),
                    )
                    add(
                        AsyncActionItem(
                            id = ID_SYSTEM_RESTART,
                            title = words(R.string.gaming_restart),
                            confirmTitle = "Restart?",
                            run = { _, _ -> PowerAction.RESTART.run(dev.droidtop.runtime.tasks.TaskManager.shell) },
                        ),
                    )
                }
                // Where the sound goes (speaker, headphones, Bluetooth): the
                // system's own output switcher, which an app cannot replace.
                add(
                    ActionItem(
                        id = ID_AUDIO_OUTPUT,
                        title = words(R.string.gaming_audio_output),
                        subtitle = words(R.string.gaming_opens_android_s_output_switcher),
                        run = { ctx -> SettingsLaunch.start(ctx, controls.audioOutputIntent()) },
                    ),
                )
                // No Social or Updates tile (Droidtop/tracker#621 M5): Social is a section of the Quick Menu's own
                // rail, and Updates a page of Downloads and Updates, which the Downloads section hosts.
            },
        ),
        CatalogGroup(
            id = GROUP_OTHER_SHELLS,
            title = words(R.string.gaming_other_shells),
            icon = CatalogIcon.MODES,
            // With the modes: each mode's own settings page sits beside the modes themselves.
            category = "Modes",
            items = listOf(
                NestedScreenItem(
                    id = ID_DESKTOP_SETTINGS,
                    title = words(R.string.gaming_desktop_mode),
                    subtitle = words(R.string.gaming_settings_for_the_desktop_shell),
                    registryId = "desktop_settings",
                    icon = CatalogIcon.DESKTOP,
                ),
                SubScreenItem(
                    id = ID_STANDARD_SETTINGS,
                    // Named for its mode, as Desktop's row beside it is; the page it opens is titled Settings
                    // (Droidtop/tracker#827 F0: "Home settings" is renamed Settings).
                    title = words(R.string.gaming_mobile_mode),
                    subtitle = words(R.string.gaming_mobile_mode_s_home_screen_icons),
                    fragmentClassName = "app.murinelauncher.settings.SettingsRootFragment",
                    icon = CatalogIcon.STANDARD,
                ),
            ),
        ),
    )

    private fun defaultSectionItem(context: Context) = ChoiceItem(
        id = ID_DEFAULT_SECTION,
        title = words(R.string.gaming_default_section),
        // Home, Gaming's two game tabs (docs/SPEC.md 7i, 2026-10-01) and Apps.
        options = listOf(
            ChoiceOption("home", "Home"), ChoiceOption("pc", "PC Games"),
            ChoiceOption("games", "Retro Games"), ChoiceOption("apps", "Apps"),
        ),
        current = CatalogPrefs.prefs(context).getString(ID_DEFAULT_SECTION, "home"),
        onSelect = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putString(ID_DEFAULT_SECTION, value).apply()
        },
    )

    private fun showHintsItem(context: Context) = ToggleItem(
        id = ID_SHOW_HINTS,
        title = words(R.string.gaming_show_button_hints),
        subtitle = words(R.string.gaming_show_the_a_b_y_button),
        current = CatalogPrefs.prefs(context).getBoolean(ID_SHOW_HINTS, true),
        onToggle = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putBoolean(ID_SHOW_HINTS, value).apply()
        },
    )

    /**
     * Gaming's own motion switch (docs/SPEC.md "Gaming motion and focus"): off,
     * every animation the shell's chrome plays is an instant change. Android's
     * "Remove animations" turns it off too, whatever this says.
     */
    private fun animationsItem(context: Context) = ToggleItem(
        id = ID_ANIMATIONS,
        title = words(R.string.gaming_animations),
        subtitle = words(R.string.gaming_focus_panels_and_pages_move_off),
        current = animationsOn(context),
        onToggle = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putBoolean(ID_ANIMATIONS, value).apply()
        },
    )

    /** The Animations switch's value: on unless the person turned it off. */
    fun animationsOn(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(ID_ANIMATIONS, true)

    /**
     * The interface sounds' own volume (docs/SPEC.md "Interface sounds"; owner, 2026-10-30, Droidtop/tracker#836:
     * "navigation sounds should get their own volume"): every cue role, whether the theme or droidtop's own set
     * supplies it, at this level of the media volume; 0 is off, as the switch it replaced was. In Settings and in the
     * Audio controls the Quick Menu and the companion draw. A game's own audio is not touched.
     */
    fun navigationSoundsItem(context: Context) = SliderItem(
        id = ID_NAVIGATION_SOUNDS,
        title = words(R.string.gaming_navigation_sounds),
        subtitle = words(R.string.gaming_sounds_for_moving_choosing_going_back),
        min = 0,
        max = NAVIGATION_SOUNDS_MAX,
        current = navigationSoundsVolume(context),
        onChange = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putInt(ID_NAVIGATION_SOUNDS, value).apply() },
    )

    /** The interface sounds' volume, 0 to [NAVIGATION_SOUNDS_MAX]; one who had turned the old switch off starts at 0. */
    fun navigationSoundsVolume(context: Context): Int {
        val prefs = CatalogPrefs.prefs(context)
        return prefs.getInt(ID_NAVIGATION_SOUNDS, if (prefs.getBoolean(ID_NAVIGATION_SOUNDS_SWITCH, true)) NAVIGATION_SOUNDS_DEFAULT else 0)
    }

    const val NAVIGATION_SOUNDS_MAX = 100
    // 40, not 70 (owner on the console, dev.2778: "the nav noises are VERY harsh"): the bundled samples peak at
    // about -1 dBFS, and SoundPool's linear gain at 0.4 brings them to about -9 dBFS under game audio.
    private const val NAVIGATION_SOUNDS_DEFAULT = 40

    /**
     * What each screen is for (docs/SPEC.md 4c, ScreenRoles; Droidtop/tracker#776): one row per screen other than the one
     * droidtop started on, which holds the menus unless another screen is the main one. A screen nobody answered for
     * says so; droidtop asks once when one is first connected, and this is where the answer is changed. Reads the
     * display list and preferences: built off the main thread like the rest of this catalog.
     */
    private fun screenRoleItems(context: Context): List<CatalogItem> {
        val outputs = dev.droidtop.runtime.DisplayOutputRepository(context).currentOutputsSnapshot()
        val names = dev.droidtop.runtime.ScreenNaming.names(context, outputs)
        val saved = dev.droidtop.runtime.ScreenRoles.saved(context)
        return outputs.filter { it.androidDisplayId != android.view.Display.DEFAULT_DISPLAY }.map { output ->
            val facts = dev.droidtop.runtime.ScreenNaming.facts(output)
            val kind = if (facts.screenClass == dev.droidtop.runtime.ScreenClass.BUILT_IN) "Built-in" else "External"
            val answered = saved[facts.uniqueId]
            ChoiceItem(
                id = ID_SCREEN_ROLE_PREFIX + facts.uniqueId,
                title = names[output.androidDisplayId] ?: output.name.ifBlank { dev.droidtop.runtime.ScreenNames.EXTERNAL },
                subtitle = "$kind, ${facts.nativeWidthPx}x${facts.nativeHeightPx}. What droidtop shows on it",
                options = listOfNotNull(ChoiceOption("", "Not answered yet").takeIf { answered == null }) +
                    dev.droidtop.runtime.ScreenRole.entries.map { ChoiceOption(it.name, it.label) },
                current = answered?.name.orEmpty(),
                onSelect = { ctx, value ->
                    dev.droidtop.runtime.ScreenRole.entries.firstOrNull { it.name == value }
                        ?.let { dev.droidtop.runtime.ScreenRoles.set(ctx, facts.uniqueId, it) }
                },
            )
        }
    }

    /**
     * A radio switched in place through the `priv.shell` provider's own command ([SystemControls.radioCommand]),
     * off the main thread; only built when a provider is running.
     */
    private fun radioToggle(
        context: Context,
        radio: dev.droidtop.runtime.systemstatus.SystemControls.Radio,
        id: String,
        title: String,
    ): ToggleItem {
        val controls = dev.droidtop.runtime.systemstatus.SystemControls
        return ToggleItem(
            id = id,
            title = title,
            subtitle = words(R.string.gaming_switches_x, title),
            current = controls.radioOn(context, radio) == true,
            onToggle = { ctx, on ->
                // Never a switch that silently does nothing (tracker#581): when Android did not end up in the state
                // asked for, its own screen for the radio opens at once.
                val switched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    controls.switchRadio(ctx, dev.droidtop.runtime.tasks.TaskManager.shell, radio, on)
                }
                if (!switched) {
                    // Why, before Android's screen opens (Droidtop/tracker#851): with nothing privileged behind the
                    // provider, the switch could never have worked, and the person is told what would make it work.
                    val why = dev.droidtop.runtime.tasks.TaskManager.shell.unavailableReason()
                    Notices.flash(
                        if (why != null) "$title wasn't switched: $why. Android's screen for it is open" else "Android did not switch $title, so its screen is open",
                        tone = NoticeTone.Caution,
                        context = ctx,
                    )
                    SettingsLaunch.start(ctx, controls.radioSettingsIntent(radio))
                }
            },
        )
    }

    /**
     * Power for the game in front (docs/SPEC.md "Power policy", Droidtop/tracker#841): one catalog item, drawn by the
     * Quick Menu's Performance section, the companion's System > Power card and its Game tab. Each press steps Auto,
     * Saver, Standard, Performance; a level is this game's pick and holds on its next launches, Auto leaves it to the
     * policy (Auto power on) or to Android (off). The value says what is in force and why. Null without a running
     * `priv.shell` provider or without a game droidtop launched. Built off the main thread.
     */
    fun performanceModeItem(): AsyncActionItem? {
        val shell = dev.droidtop.runtime.tasks.TaskManager.shell
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return null
        val context = dev.droidtop.runtime.power.PowerController.context ?: return null
        val game = dev.droidtop.runtime.tasks.LaunchLedger.last?.packageName ?: return null
        val pick = dev.droidtop.runtime.power.PowerPrefs.gamePick(context, game)
        val auto = dev.droidtop.runtime.power.PowerPrefs.auto(context)
        val decision = dev.droidtop.runtime.power.PowerController.decide(context, game)
        return AsyncActionItem(
            id = ID_PERFORMANCE_MODE,
            title = words(R.string.gaming_power),
            subtitle = words(R.string.gaming_how_hard_the_game_in_front),
            value = powerValue(pick, auto, decision),
            run = { ctx, onStatus ->
                onStatus("Setting...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val next = nextPowerPick(pick)
                    dev.droidtop.runtime.power.PowerPrefs.setGamePick(ctx, game, next)
                    val outcome = dev.droidtop.runtime.power.PowerController.applyFor(ctx, game)
                    val now = dev.droidtop.runtime.power.PowerController.decide(ctx, game)
                    when (outcome) {
                        dev.droidtop.runtime.power.StandardPowerLayer.Outcome.IGNORED -> "Android ignores this app"
                        dev.droidtop.runtime.power.StandardPowerLayer.Outcome.FAILED -> "Android refused"
                        null -> {
                            // Back to Auto with Auto power off: Android's own mode for it again.
                            dev.droidtop.runtime.power.StandardPowerLayer.restore(ctx, shell, game)
                            powerValue(next, false, now)
                        }
                        else -> powerValue(next, dev.droidtop.runtime.power.PowerPrefs.auto(ctx), now)
                    }
                }
            },
        )
    }

    /** The order a press steps through: Auto, Saver, Standard, Performance, Auto. Pure. */
    internal fun nextPowerPick(pick: dev.droidtop.runtime.power.PowerChoice): dev.droidtop.runtime.power.PowerChoice {
        val levels = listOf(dev.droidtop.runtime.power.PowerLevel.SAVER, dev.droidtop.runtime.power.PowerLevel.STANDARD, dev.droidtop.runtime.power.PowerLevel.PERFORMANCE)
        val at = (pick as? dev.droidtop.runtime.power.PowerChoice.Fixed)?.let { levels.indexOf(it.level) } ?: -1
        return if (at + 1 < levels.size) dev.droidtop.runtime.power.PowerChoice.Fixed(levels[at + 1]) else dev.droidtop.runtime.power.PowerChoice.Auto
    }

    /** What the row says: the level in force, and why when heat or the battery holds it lower. Pure. */
    internal fun powerValue(pick: dev.droidtop.runtime.power.PowerChoice, auto: Boolean, decision: dev.droidtop.runtime.power.PowerDecision): String {
        fun word(level: dev.droidtop.runtime.power.PowerLevel) = level.name.lowercase().replaceFirstChar { it.uppercase() }
        val capped = decision.effective < decision.requested
        return when {
            pick is dev.droidtop.runtime.power.PowerChoice.Fixed && capped -> "${word(decision.effective)}: ${decision.why}"
            pick is dev.droidtop.runtime.power.PowerChoice.Fixed -> word(pick.level)
            !auto -> "Auto (off)"
            capped -> "Auto: ${word(decision.effective)}, ${decision.why}"
            else -> "Auto: ${word(decision.effective)}"
        }
    }

    /**
     * Android's privacy dashboard (which apps used the camera, microphone and location lately, Android 12 and later),
     * else its Privacy settings. The companion's System > Privacy card, and Settings > System > Android.
     */
    fun privacyDashboardItem(): ActionItem = ActionItem(
        id = ID_PRIVACY_DASHBOARD,
        title = words(R.string.gaming_privacy_dashboard),
        subtitle = words(R.string.gaming_which_apps_used_the_camera_microphone),
        run = { ctx ->
            val dashboard = Intent("android.intent.action.REVIEW_PERMISSION_USAGE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val intent = if (android.os.Build.VERSION.SDK_INT >= 31 &&
                dashboard.resolveActivity(ctx.packageManager) != null
            ) dashboard else fallback
            SettingsLaunch.start(ctx, intent)
        },
    )

    /** The tabs a person can put on the companion's bar or open it on, with their names. Game comes by itself. */
    private val COMPANION_TAB_CHOICES = listOf(
        ControlPanel.HOME to "Home",
        ControlPanel.SYSTEM to "System",
        ControlPanel.APPS to "Apps",
        ControlPanel.PERFORMANCE to "Performance",
        ControlPanel.INPUT to "Input",
        ControlPanel.SOCIAL to "Social",
        ControlPanel.PLUGINS to "Plugins",
        ControlPanel.CONNECTIONS to "Network",
    )

    /** The names of plugin panels last offered as tab choices, so a bar's summary can name them without reading manifests. */
    @Volatile private var companionPluginTabNames: Map<String, String> = emptyMap()

    private val COMPANION_MODE_NAMES = mapOf("GAMING" to "Gaming", "STANDARD" to "Mobile", "DESKTOP" to "Desktop")

    /**
     * The Companion group (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): the bar's tabs and the
     * opening tab per mode, what happens when a game starts, and a reset. Kid and Kiosk hide the group
     * ([ControlAccess.GROUP_COMPANION]). Reads [CompanionPrefs], loaded by the caller off the main thread.
     */
    private fun companionTabsGroup(context: Context): CatalogGroup {
        val settings = CompanionPrefs.settings.value
        val tabOptions = COMPANION_TAB_CHOICES.map { (panel, label) -> ChoiceOption(CompanionPrefs.id(panel), label) }
        fun names(ids: List<String>) = ids.mapNotNull { id ->
            COMPANION_TAB_CHOICES.firstOrNull { CompanionPrefs.id(it.first) == id }?.second ?: companionPluginTabNames[id]
        }.joinToString(", ")
        val perMode = CompanionPrefs.MODES.map { mode ->
            val name = COMPANION_MODE_NAMES.getValue(mode)
            NestedScreenItem(
                id = "companion_tabs_$mode",
                title = words(R.string.gaming_in_x, name),
                subtitle = words(R.string.gaming_the_tabs_on_the_companion_s, name),
                valueLabel = { _ -> names(CompanionPrefs.settings.value.chosen(mode)) },
                inline = CatalogScreen(
                    id = "companion_tabs_$mode",
                    title = words(R.string.gaming_companion_in_x, name),
                    groups = { ctx ->
                        CompanionPrefs.load(ctx)
                        val now = CompanionPrefs.settings.value
                        val chosen = now.chosen(mode)
                        // One plugin's panel can be a tab of its own (docs/SPEC.md "The companion's tabs").
                        val pluginTabs = PluginShellHooks.companionTabs(ctx, mode.lowercase())
                        companionPluginTabNames = companionPluginTabNames + pluginTabs
                        val modeTabOptions = tabOptions + pluginTabs.map { (id, label) -> ChoiceOption(id, label) }
                        listOf(
                            CatalogGroup(
                                id = "companion_tabs_${mode}_bar",
                                title = words(R.string.gaming_tabs_on_the_bar),
                                items = (0 until CompanionPrefs.MAX_CHOSEN).map { slot ->
                                    ChoiceItem(
                                        id = "companion_tab_${mode}_$slot",
                                        title = words(R.string.gaming_tab_x, slot + 1),
                                        subtitle = words(R.string.gaming_tabs_not_on_the_bar_are),
                                        options = listOf(ChoiceOption("", "None")) + modeTabOptions,
                                        current = chosen.getOrNull(slot).orEmpty(),
                                        onSelect = { c, value ->
                                            CompanionPrefs.setChosen(
                                                c,
                                                mode,
                                                CompanionPrefs.placeTab(CompanionPrefs.settings.value.chosen(mode), slot, value),
                                            )
                                        },
                                    )
                                },
                            ),
                            CatalogGroup(
                                id = "companion_tabs_${mode}_opening",
                                title = null,
                                items = listOf(
                                    ChoiceItem(
                                        id = "companion_opening_$mode",
                                        title = words(R.string.gaming_opening_tab),
                                        subtitle = words(R.string.gaming_the_tab_the_companion_shows_when),
                                        options = listOf(
                                            ChoiceOption(
                                                CompanionPrefs.OPEN_DEFAULT,
                                                if (mode == "DESKTOP") "Input (default)" else "Home (default)",
                                            ),
                                            ChoiceOption(CompanionPrefs.OPEN_LAST, "The last one used"),
                                        ) + modeTabOptions,
                                        current = now.opening(mode),
                                        onSelect = { c, value -> CompanionPrefs.setOpening(c, mode, value) },
                                    ),
                                ),
                            ),
                        )
                    },
                ),
            )
        }
        return CatalogGroup(
            id = ControlAccess.GROUP_COMPANION,
            title = words(R.string.gaming_tabs),
            items = perMode + listOf(
                ChoiceItem(
                    id = "companion_game_start",
                    title = words(R.string.gaming_when_a_game_starts),
                    subtitle = words(R.string.gaming_which_tab_the_companion_turns_to),
                    options = listOf(
                        ChoiceOption(CompanionPrefs.GAME_START_RUNNER, "Game, or Input for streams (default)"),
                        ChoiceOption(CompanionPrefs.GAME_START_NONE, "Stay where it is"),
                    ) + tabOptions,
                    current = settings.onGameStart,
                    onSelect = { c, value -> CompanionPrefs.setOnGameStart(c, value) },
                ),
                ToggleItem(
                    id = "companion_pip",
                    title = words(R.string.gaming_picture_in_picture_on_the_companion),
                    subtitle = words(R.string.gaming_a_video_shrunk_on_the_main),
                    current = settings.pipToCompanion,
                    onToggle = { c, on -> CompanionPrefs.setPipToCompanion(c, on) },
                ),
                ToggleItem(
                    id = "companion_ask_stopping",
                    title = words(R.string.gaming_ask_before_stopping),
                    subtitle = words(R.string.gaming_quit_restart_kill_stop_and_clear),
                    current = settings.askBeforeStopping,
                    onToggle = { c, on -> CompanionPrefs.setAskBeforeStopping(c, on) },
                ),
                ToggleItem(
                    id = "companion_ask_load",
                    title = words(R.string.gaming_ask_before_load_and_overwrite),
                    subtitle = words(R.string.gaming_loading_a_save_state_or_saving),
                    current = settings.askBeforeLoad,
                    onToggle = { c, on -> CompanionPrefs.setAskBeforeLoad(c, on) },
                ),
                NestedScreenItem(
                    id = "companion_trackpad",
                    title = words(R.string.gaming_trackpad),
                    subtitle = words(R.string.gaming_how_the_companion_s_input_trackpad),
                    inline = trackpadScreen(),
                ),
                ChoiceItem(
                    id = "companion_idle",
                    title = words(R.string.gaming_dim_the_companion_screen),
                    subtitle = words(R.string.gaming_with_no_input_it_dims_after),
                    options = dev.droidtop.runtime.CompanionIdle.DIM_CHOICES_MS.map {
                        ChoiceOption(it.toString(), dev.droidtop.runtime.CompanionIdle.dimLabel(it))
                    },
                    current = settings.idleDimMs.toString(),
                    onSelect = { c, value -> value.toLongOrNull()?.let { CompanionPrefs.setIdleDim(c, it) } },
                ),
                ChoiceItem(
                    id = "companion_wake",
                    title = words(R.string.gaming_wake_the_companion_screen),
                    subtitle = words(R.string.gaming_what_turns_the_companion_screen_back),
                    options = dev.droidtop.runtime.CompanionIdle.Wake.entries.map { ChoiceOption(it.key, it.label) },
                    current = settings.wake,
                    onSelect = { c, value -> CompanionPrefs.setWake(c, value) },
                ),
                ChoiceItem(
                    id = "companion_handle_edge",
                    title = words(R.string.gaming_show_tabs_handle),
                    subtitle = words(R.string.gaming_input_fills_the_companion_screen_the),
                    options = listOf(
                        ChoiceOption(CompanionPrefs.HANDLE_BOTTOM, "Bottom (default)"),
                        ChoiceOption(CompanionPrefs.HANDLE_TOP, "Top"),
                    ),
                    current = settings.handleEdge,
                    onSelect = { c, value -> CompanionPrefs.setHandleEdge(c, value) },
                ),
                ChoiceItem(
                    id = "companion_low_battery",
                    title = words(R.string.gaming_low_battery_line),
                    subtitle = words(R.string.gaming_a_line_on_the_companion_s),
                    options = listOf(ChoiceOption("0", "Off (default)")) +
                        listOf(10, 15, 20, 30).map { ChoiceOption(it.toString(), "At $it%") },
                    current = settings.lowBattery.toString(),
                    onSelect = { c, value -> CompanionPrefs.setLowBattery(c, value.toIntOrNull() ?: 0) },
                ),
                ChoiceItem(
                    id = "companion_message_text",
                    title = words(R.string.gaming_show_message_text),
                    subtitle = words(R.string.gaming_whether_notifications_on_the_companion_show),
                    options = dev.droidtop.runtime.systemstatus.NotificationRows.MessageText.entries.map { ChoiceOption(it.key, it.label) },
                    current = settings.messageText,
                    onSelect = { c, value -> CompanionPrefs.setMessageText(c, value) },
                ),
                ActionItem(
                    id = "companion_reset",
                    title = words(R.string.gaming_reset_the_companion_to_defaults),
                    subtitle = words(R.string.gaming_puts_the_bar_the_opening_tab),
                    confirmTitle = "Reset the companion?",
                    run = { c -> CompanionPrefs.reset(c); PinnedControls.reset(c) },
                ),
            ),
        )
    }

    /**
     * The companion trackpad's settings (docs/SPEC.md "The companion's tabs", Input; slice C12): libinput's options with
     * libinput's defaults, read by the trackpad when Input opens. Every row says what it does for the person.
     */
    private fun trackpadScreen(): CatalogScreen = CatalogScreen(
        id = "companion_trackpad",
        title = words(R.string.gaming_trackpad),
        groups = { ctx ->
            CompanionPrefs.load(ctx)
            val t = CompanionPrefs.settings.value.trackpad
            fun toggle(id: String, title: String, subtitle: String, current: Boolean, set: (TrackpadSettings, Boolean) -> TrackpadSettings) =
                ToggleItem(
                    id = "companion_trackpad_$id",
                    title = title,
                    subtitle = subtitle,
                    current = current,
                    onToggle = { c, on -> CompanionPrefs.setTrackpad(c) { set(it, on) } },
                )
            listOf(
                CatalogGroup(
                    id = "companion_trackpad",
                    title = null,
                    items = listOf(
                        SliderItem(
                            id = "companion_trackpad_speed",
                            title = words(R.string.gaming_pointer_speed),
                            subtitle = words(R.string.gaming_how_far_the_pointer_moves_for),
                            min = -10,
                            max = 10,
                            current = t.speed,
                            onChange = { c, value -> CompanionPrefs.setTrackpad(c) { it.copy(speed = value) } },
                        ),
                        toggle("tap", "Tap to click", "A tap clicks, and tap then hold drags", t.tapToClick) { s, on -> s.copy(tapToClick = on) },
                        ChoiceItem(
                            id = "companion_trackpad_tap_ms",
                            title = words(R.string.gaming_tap_time),
                            subtitle = words(R.string.gaming_how_long_a_touch_can_last),
                            options = listOf(120, 180, 250, 350).map { ChoiceOption(it.toString(), if (it == 180) "$it ms (default)" else "$it ms") },
                            current = t.tapMs.toString(),
                            onSelect = { c, value -> CompanionPrefs.setTrackpad(c) { it.copy(tapMs = value.toIntOrNull() ?: 180) } },
                        ),
                        ChoiceItem(
                            id = "companion_trackpad_drag_ms",
                            title = words(R.string.gaming_hold_to_drag),
                            subtitle = words(R.string.gaming_how_soon_after_a_tap_your),
                            options = listOf(200, 300, 500).map { ChoiceOption(it.toString(), if (it == 300) "$it ms (default)" else "$it ms") },
                            current = t.dragWindowMs.toString(),
                            onSelect = { c, value -> CompanionPrefs.setTrackpad(c) { it.copy(dragWindowMs = value.toIntOrNull() ?: 300) } },
                        ),
                        toggle("two_scroll", "Two-finger scroll", "Two fingers moving scroll the page", t.twoFingerScroll) { s, on -> s.copy(twoFingerScroll = on) },
                        toggle("two_right", "Two-finger tap right-clicks", "A tap with two fingers opens the menu a right click opens", t.twoFingerRightClick) { s, on -> s.copy(twoFingerRightClick = on) },
                        toggle("long_press", "Long press right-clicks", "Holding one finger still opens the menu a right click opens", t.longPressRightClick) { s, on -> s.copy(longPressRightClick = on) },
                        toggle("three_middle", "Three-finger tap middle-clicks", "A tap with three fingers pastes or opens a link in a new tab", t.threeFingerMiddleClick) { s, on -> s.copy(threeFingerMiddleClick = on) },
                        toggle("natural", "Natural scrolling", "The page moves with your fingers, as on a phone", t.naturalScroll) { s, on -> s.copy(naturalScroll = on) },
                        toggle("momentum", "Scroll keeps going", "After a quick scroll the page coasts and slows down", t.momentum) { s, on -> s.copy(momentum = on) },
                        toggle("drag_lock", "Drag lock", "While dragging, lifting your finger keeps holding; the next tap lets go", t.dragLock) { s, on -> s.copy(dragLock = on) },
                    ),
                ),
            )
        },
    )

    /**
     * Typing on the add-on display (docs/SPEC.md 4c, "Typing on the add-on display", Droidtop/tracker#314). With
     * the elevated helper, a switch for Android's own keyboard on the second screen (on by default, given back when
     * off); without it the switch is not drawn. Then one row whose value says how typing into other apps on that
     * screen works right now ([AddonKeyboardRules.appsKeyboard]), and whose action is the one step that would make
     * it work: choosing droidtop's keyboard, or allowing it to draw over other apps. Then the accessibility route's
     * row (Android's accessibility settings, where the user turns droidtop's service on), and while Android 13+
     * keeps that switch locked for a sideloaded droidtop, the one row that opens App info.
     */
    /**
     * One row per connected screen to give it a name of the person's own (docs/SPEC.md 4c, "Screen names",
     * Droidtop/tracker#162): the launch chooser, the running apps list and every row that names a screen use it.
     * Stored per Android's unique id for the display, so the name stays with the screen. Reads the display list and a
     * preferences file: built off the main thread like the rest of this catalog.
     */
    private fun screenNameItems(context: Context): List<CatalogItem> {
        val outputs = dev.droidtop.runtime.DisplayOutputRepository(context).currentOutputsSnapshot().sortedBy { it.androidDisplayId }
        val names = dev.droidtop.runtime.ScreenNaming.names(context, outputs)
        val custom = dev.droidtop.runtime.ScreenNaming.customNames(context)
        return outputs.map { output ->
            val facts = dev.droidtop.runtime.ScreenNaming.facts(output)
            val kind = if (facts.screenClass == dev.droidtop.runtime.ScreenClass.BUILT_IN) "Built-in" else "External"
            TextInputItem(
                id = "screen_name_${output.uniqueId}",
                title = words(R.string.gaming_name_x, names[output.androidDisplayId] ?: output.name),
                subtitle = "$kind, Android calls it \"${output.name.ifBlank { "display ${output.androidDisplayId}" }}\". " +
                    "Leave empty for droidtop's own name",
                value = custom[output.uniqueId].orEmpty(),
                onChange = { ctx, value -> dev.droidtop.runtime.ScreenNaming.rename(ctx, output.uniqueId, value) },
            )
        }
    }

    // The screensaver's page (Droidtop/tracker#726): when it shows, what it shows, dimmed or not, and the game's name.
    private fun screensaverItem(context: Context) = NestedScreenItem(
        id = ID_SCREENSAVER_PAGE,
        title = words(R.string.gaming_screensaver),
        subtitle = words(R.string.gaming_screensaver_page_subtitle),
        valueLabel = { ctx -> ScreensaverPrefs.mode(ctx).label },
        icon = CatalogIcon.SCREENSAVER,
        inline = CatalogScreen(
            id = "screensaver",
            title = words(R.string.gaming_screensaver),
            groups = { ctx -> listOf(CatalogGroup(id = "screensaver_rows", title = null, items = screensaverRows(ctx))) },
        ),
    )

    private fun screensaverRows(context: Context): List<CatalogItem> {
        val look = ScreensaverPrefs.look(context)
        return listOf(
            ChoiceItem(
                id = ID_SCREENSAVER,
                title = words(R.string.gaming_screensaver_when),
                // Android's own screen timeout still turns the
                // panel off, and it wins when it is the shorter
                // (docs/SPEC.md 7f, "Screensaver").
                subtitle = words(R.string.gaming_screensaver_when_subtitle),
                options = ScreensaverMode.entries.map { ChoiceOption(it.name, it.label) },
                current = ScreensaverPrefs.mode(context).name,
                onSelect = { ctx, value ->
                    ScreensaverPrefs.setMode(
                        ctx,
                        runCatching { ScreensaverMode.valueOf(value) }.getOrDefault(ScreensaverMode.OFF),
                    )
                },
            ),
            ChoiceItem(
                id = ID_SCREENSAVER_CONTENT,
                title = words(R.string.gaming_screensaver_shows),
                options = ScreensaverContent.entries.map { ChoiceOption(it.name, it.label) },
                current = look.content.name,
                onSelect = { ctx, value ->
                    runCatching { ScreensaverContent.valueOf(value) }.getOrNull()?.let { ScreensaverPrefs.setContent(ctx, it) }
                },
            ),
            ToggleItem(
                id = ID_SCREENSAVER_DIM,
                title = words(R.string.gaming_screensaver_dim),
                subtitle = words(R.string.gaming_screensaver_dim_subtitle),
                current = look.dim,
                onToggle = { ctx, on -> ScreensaverPrefs.setDim(ctx, on) },
            ),
            ToggleItem(
                id = ID_SCREENSAVER_NAME,
                title = words(R.string.gaming_screensaver_show_name),
                subtitle = words(R.string.gaming_screensaver_show_name_subtitle),
                current = look.showName,
                onToggle = { ctx, on -> ScreensaverPrefs.setShowName(ctx, on) },
            ),
        )
    }

    // The Steam Deck's model (docs/SPEC.md 7f, "Sleep and return to game"): the console does not sleep
    // on its own while a game runs, unless the person asks for Android's timer.
    private fun gameAwakeItem(context: Context) = ChoiceItem(
        id = ID_GAME_AWAKE,
        title = words(R.string.gaming_while_a_game_runs),
        subtitle = null,
        options = GameAwakeMode.entries.map { ChoiceOption(it.name, it.label) },
        current = GameAwakePrefs.mode(context).name,
        onSelect = { ctx, value ->
            GameAwakePrefs.setMode(
                ctx,
                runCatching { GameAwakeMode.valueOf(value) }.getOrDefault(GameAwakeMode.NEVER_SLEEP),
            )
        },
    )

    private fun secondScreenKeyboardItems(context: Context): List<CatalogItem> = buildList {
        val elevated = runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
        if (elevated) {
            add(
                ToggleItem(
                    id = dev.droidtop.runtime.keyboard.AddonKeyboard.KEY_ANDROID_KEYBOARD,
                    title = words(R.string.gaming_android_keyboard_on_second_screen),
                    current = dev.droidtop.runtime.keyboard.AddonKeyboard.androidKeyboardOnSecondScreen(context),
                    onToggle = { ctx, value -> dev.droidtop.runtime.keyboard.AddonKeyboard.setAndroidKeyboardOnSecondScreen(ctx, value) },
                ),
            )
        }
        val keyboard = dev.droidtop.runtime.keyboard.AddonKeyboard
        keyboard.load(context)
        add(
            ChoiceItem(
                id = ID_DISPLAY_KEYBOARD_PLACEMENT,
                title = words(R.string.gaming_keyboard_displays_on),
                // "Companion" that cannot be honoured says so, and why, instead of reading as if it were in effect
                // (Droidtop/tracker#523).
                subtitle = when (keyboard.lastCompanionFallback.value.takeIf { keyboard.placement.value == dev.droidtop.runtime.keyboard.KeyboardPlacement.COMPANION }) {
                    dev.droidtop.runtime.keyboard.CompanionFallback.COMPANION_KEYBOARD_OFF -> words(R.string.gaming_keyboard_companion_off)
                    dev.droidtop.runtime.keyboard.CompanionFallback.NO_COMPANION_ON_OTHER_SCREEN -> words(R.string.gaming_keyboard_no_companion)
                    null -> null
                },
                options = dev.droidtop.runtime.keyboard.KeyboardPlacement.entries.map { ChoiceOption(it.key, it.label) },
                current = keyboard.placement.value.key,
                onSelect = { ctx, value -> keyboard.setPlacement(ctx, dev.droidtop.runtime.keyboard.KeyboardPlacement.fromKey(value)) },
            ),
        )
        // The companion's own settings: what the companion offers, apart from where the keyboard shows.
        add(
            NestedScreenItem(
                id = ID_DISPLAY_COMPANION,
                // Its category's name in the Settings column ("Companion screen", CATEGORY_ORDER); every category
                // has an icon (Droidtop/tracker#676).
                title = words(R.string.gaming_companion_screen),
                icon = CatalogIcon.DISPLAY,
                inline = CatalogScreen(
                    id = ID_DISPLAY_COMPANION,
                    title = words(R.string.gaming_companion),
                    groups = { ctx ->
                        keyboard.load(ctx)
                        CompanionHomePrefs.load(ctx)
                        CompanionPrefs.load(ctx)
                        val home = CompanionHomePrefs.layout.value
                        // Kid and Kiosk never reach the companion's own preferences (ControlAccess).
                        ControlAccess.filter(UiModePrefs.get(ctx), listOf(
                            companionTabsGroup(ctx),
                            // The companion screen's own orientation lock, apart from each mode's (tracker#213).
                            CatalogGroup(
                                id = "${ID_DISPLAY_COMPANION}_screen",
                                title = words(R.string.gaming_screen),
                                items = listOf(
                                    ToggleItem(
                                        id = CompanionOrientation.KEY_LOCK_LANDSCAPE,
                                        title = words(R.string.gaming_lock_to_landscape),
                                        current = CompanionOrientation.lockLandscape(ctx),
                                        onToggle = { c, on -> CompanionOrientation.setLockLandscape(c, on) },
                                    ),
                                ),
                            ),
                            // Which sections the companion's Home shows; folding one is done on Home itself.
                            CatalogGroup(
                                id = "${ID_DISPLAY_COMPANION}_home",
                                title = words(R.string.gaming_home),
                                items = CompanionHomeSection.entries.map { section ->
                                    ToggleItem(
                                        id = CompanionHomePrefs.itemId(section),
                                        title = section.label,
                                        // Every section but these two draws nothing while it has nothing to show,
                                        // so an ON row with no section on Home is not a fault (Droidtop/tracker#328).
                                        subtitle = if (section in ALWAYS_SHOWN_HOME) null else "Appears when there is something to show",
                                        current = home.shows(section),
                                        onToggle = { c, on -> CompanionHomePrefs.setShown(c, section, on) },
                                    )
                                },
                            ),
                            CatalogGroup(
                                id = "${ID_DISPLAY_COMPANION}_keyboard",
                                title = words(R.string.gaming_keyboard),
                                items = listOf(
                                    ToggleItem(
                                        id = keyboard.KEY_COMPANION_HOSTS,
                                        title = words(R.string.gaming_keyboard_for_the_other_screen),
                                        current = keyboard.companionHostsKeyboard.value,
                                        onToggle = { c, on -> keyboard.setCompanionHostsKeyboard(c, on) },
                                    ),
                                ),
                            ),
                        ))
                    },
                ),
            ),
        )
        val androidShows = dev.droidtop.runtime.keyboard.AddonKeyboard.localDisplays.value.isNotEmpty()
        val ownActive = Keyboards.ownKeyboardActive(context)
        val overlay = android.provider.Settings.canDrawOverlays(context)
        val accessibility = dev.droidtop.runtime.keyboard.AccessibilityKeyboard.isEnabled(context)
        val state = dev.droidtop.runtime.keyboard.AddonKeyboardRules.appsKeyboard(keyboard.controlsKeyboard, androidShows, ownActive, overlay, accessibility)
        add(
            ActionItem(
                id = ID_DISPLAY_KEYBOARD,
                title = words(R.string.gaming_keyboard_for_apps_on_second_screen),
                value = state.label,
                run = { ctx ->
                    if (state == dev.droidtop.runtime.keyboard.AppsKeyboard.NEEDS_OVERLAY) {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        Keyboards.showPicker(ctx)
                    }
                },
            ),
        )
        add(
            ActionItem(
                id = ID_DISPLAY_KEYBOARD_ACCESSIBILITY,
                title = words(R.string.gaming_accessibility_keyboard_and_pointer),
                value = if (accessibility) "On" else "Off",
                run = { ctx -> dev.droidtop.runtime.keyboard.AccessibilityKeyboard.openSettings(ctx) },
            ),
        )
        val restricted = dev.droidtop.runtime.systemstatus.RestrictedSettings
        if (restricted.stepNeeded(context, dev.droidtop.runtime.systemstatus.RestrictedGrant.ACCESSIBILITY, accessibility)) {
            add(
                ActionItem(
                    id = ID_DISPLAY_KEYBOARD_RESTRICTED,
                    title = restricted.TITLE,
                    value = restricted.VALUE,
                    run = { ctx -> restricted.openAppInfo(ctx) },
                ),
            )
        }
    }

    private fun displayGameLaunchTargetItem(context: Context) = ChoiceItem(
        id = ID_DISPLAY_GAME_LAUNCH_TARGET,
        title = words(R.string.gaming_games_launch_on),
        options = listOf(
            ChoiceOption("ASK", "Ask every time (default)"),
            ChoiceOption("FOLLOW_SHELL", "Same display as the shell"),
            ChoiceOption("BUILT_IN", "Built-in screen"),
            ChoiceOption("SECOND", "Second display"),
        ),
        current = CatalogPrefs.prefs(context).getString(ID_DISPLAY_GAME_LAUNCH_TARGET, "ASK"),
        onSelect = { ctx, value -> setGameLaunchTarget(ctx, value) },
    )

    /**
     * Where every game launches: "ASK", "FOLLOW_SHELL", "BUILT_IN" or "SECOND". The one write, shared by this row and
     * the launch chooser's "Every game" rows (Droidtop/tracker#590).
     */
    fun setGameLaunchTarget(context: Context, value: String) {
        CatalogPrefs.prefs(context).edit().putString(ID_DISPLAY_GAME_LAUNCH_TARGET, value).apply()
        // The launch target is resolved during orchestration; without
        // this the new choice waits for the next display event.
        dev.droidtop.runtime.DisplayArrangement.changed()
    }

    private fun themeItem(context: Context): ChoiceItem {
        // The VALUE stays the directory id, which is what ThemePrefs
        // stores; the LABEL is the theme's own name. Settings used to show
        // the id, so the row read "slate-es-de".
        val themes = ThemeAssets.discoverThemes(context)
        return ChoiceItem(
            id = ID_THEME,
            title = words(R.string.gaming_theme),
            options = themes.map { ChoiceOption(it.name, ThemeAssets.displayName(context, it)) },
            current = ThemeAssets.activeThemeName(context),
            onSelect = { ctx, value -> ThemePrefs.set(ctx, value) },
        )
    }

    // Real ES-DE parity (its own UI Settings > Theme color scheme menu):
    // entries come from the ACTIVE theme's own capabilities.xml, labels
    // included. Null (absent from the catalog) when the theme declares
    // one or none -- there is nothing to choose.
    private fun themeColorSchemeItem(context: Context): ChoiceItem? {
        val caps = ThemeAssets.activeThemeCapabilities(context) ?: return null
        val themeName = ThemeAssets.activeThemeName(context) ?: return null
        val schemes = caps.colorSchemes
        if (schemes.size <= 1) return null
        // Automatic, the default, is the scheme whose ground matches Light or dark (docs/SPEC.md 7k, "Light and dark");
        // a named scheme is the person's own choice and is drawn as the theme wrote it, whatever the appearance.
        return ChoiceItem(
            id = ID_THEME_COLOR_SCHEME,
            title = words(R.string.gaming_theme_color_scheme),
            options = listOf(ChoiceOption(SCHEME_AUTOMATIC, "Automatic (matches Light or dark)")) +
                schemes.map { ChoiceOption(it, caps.colorSchemeLabels[it] ?: it) },
            current = ThemePrefs.colorScheme(context, themeName) ?: SCHEME_AUTOMATIC,
            onSelect = { ctx, value -> ThemePrefs.setColorScheme(ctx, themeName, value.takeIf { it != SCHEME_AUTOMATIC }) },
        )
    }

    private fun themeVariantItem(context: Context): ChoiceItem? {
        val caps = ThemeAssets.activeThemeCapabilities(context) ?: return null
        val themeName = ThemeAssets.activeThemeName(context) ?: return null
        val variants = caps.variants
        if (variants.size <= 1) return null
        return ChoiceItem(
            id = ID_THEME_VARIANT,
            title = words(R.string.gaming_theme_variant),
            options = variants.map { ChoiceOption(it, caps.variantLabels[it] ?: it) },
            current = ThemePrefs.variant(context, themeName) ?: variants.first(),
            onSelect = { ctx, value -> ThemePrefs.setVariant(ctx, themeName, value) },
        )
    }

    /**
     * ES-DE's own "THEME ASPECT RATIO" menu entry (GuiMenu.cpp:365-404):
     * the options are the active theme's whole capability list in its own
     * order, labelled by `getAspectRatioLabel`, and the first of them is
     * always "automatic" -- the resolve-against-the-screen default.
     * Stored per theme like the color scheme and the variant beside it,
     * and absent when the theme declares no ratio at all, which is
     * ES-DE's disabled "NONE DEFINED" state (:397-404).
     *
     * Until this, the setting existed and had no way in: a phone user
     * could not ask a theme with a vertical variant for its landscape
     * one, or the other way round.
     */
    private fun themeAspectRatioItem(context: Context): ChoiceItem? {
        val caps = ThemeAssets.activeThemeCapabilities(context) ?: return null
        val themeName = ThemeAssets.activeThemeName(context) ?: return null
        val ratios = caps.aspectRatios
        if (ratios.isEmpty()) return null
        return ChoiceItem(
            id = ID_THEME_ASPECT_RATIO,
            title = words(R.string.gaming_theme_aspect_ratio),
            subtitle = words(R.string.gaming_automatic_follows_the_screen_the_app),
            options = ratios.map { ChoiceOption(it, EsDeAspectRatio.labelFor(it)) },
            current = ThemePrefs.aspectRatio(context, themeName) ?: ratios.first(),
            onSelect = { ctx, value -> ThemePrefs.setAspectRatio(ctx, themeName, value) },
        )
    }

    /**
     * The icon pack Apps and Standard's drawer draw their icons with (Droidtop/tracker#508): the one choice Standard's
     * launcher keeps ([IconPacks]). System icons are the default. Not shown when no launcher registered the source.
     */
    private fun iconPackItem(context: Context): ChoiceItem? {
        val source = IconPacks.source ?: return null
        val packs = runCatching { source.installed(context) }.getOrDefault(emptyList())
        if (packs.size <= 1) return null
        return ChoiceItem(
            id = ID_ICON_PACK,
            title = words(R.string.gaming_icon_pack),
            subtitle = words(R.string.gaming_app_icons_in_apps_and_on),
            options = packs.map { (pack, label) -> ChoiceOption(pack, label) },
            current = source.selected(context),
            onSelect = { ctx, pack -> source.select(ctx, pack) },
        )
    }

    private fun appsGridColumnsItem(context: Context) = SliderItem(
        id = ID_APPS_GRID_COLUMNS,
        title = words(R.string.gaming_apps_grid_columns),
        subtitle = words(R.string.gaming_icon_density_for_the_apps_tab),
        min = MIN_APPS_GRID_COLUMNS,
        max = MAX_APPS_GRID_COLUMNS,
        current = CatalogPrefs.prefs(context).getInt(ID_APPS_GRID_COLUMNS, DEFAULT_APPS_GRID_COLUMNS),
        onChange = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putInt(ID_APPS_GRID_COLUMNS, value).apply()
        },
    )

    /**
     * The ONE "look at my games again" action, so the screen that changes
     * which folders are scanned can offer it too (ROM folders, whose own
     * subtitle says changes apply on the next rescan and used to leave the
     * user to find it elsewhere). Same id deliberately: the in-shell
     * renderer substitutes its own scan-trigger bump by id, so both places
     * get the real in-place rescan rather than one of them getting a
     * second, weaker mechanism.
     */
    fun rescanLibraryItem(): AsyncActionItem = AsyncActionItem(
        id = ID_RESCAN_LIBRARY,
        title = words(R.string.gaming_rescan_library),
        subtitle = words(R.string.gaming_look_for_new_or_changed_games),
        // One action wherever the row is: it says it started, how far it
        // is, and what it found when it finished (LibraryRescan). Selected
        // again while it runs, it stops the rescan instead of starting a
        // second one: a walk of a slow card can take minutes.
        run = { ctx, onStatus ->
            if (LibraryRescan.cancel()) "Cancelling the rescan\u2026" else LibraryRescan.run(ctx, onStatus)
        },
    )

    /**
     * "Rescan PC game folders" (docs/SPEC.md 7j "Places", Droidtop/tracker#397
     * slice E): [rescanLibraryItem] over the PC walks only, on Game sources >
     * Folders. Selected again while it runs, it stops the rescan.
     */
    fun rescanPcFoldersItem(): AsyncActionItem = AsyncActionItem(
        id = ID_RESCAN_PC_FOLDERS,
        title = RESCAN_PC_FOLDERS,
        subtitle = words(R.string.gaming_looks_in_your_game_folders_for),
        run = { ctx, onStatus ->
            if (LibraryRescan.cancel()) "Cancelling the rescan\u2026" else LibraryRescan.runPcFolders(ctx, onStatus)
        },
    )

    const val ID_RESCAN_PC_FOLDERS = "rescan_pc_folders"
    const val RESCAN_PC_FOLDERS = "Rescan PC game folders"

    private fun launchComponent(className: String, vararg extras: Pair<String, Any>): (Context) -> Unit = { ctx ->
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(ctx.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            for ((key, value) in extras) {
                when (value) {
                    is Boolean -> putExtra(key, value)
                    is String -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                }
            }
        }
        ctx.startActivity(intent)
    }
}

/**
 * What the Keyboard row does, and whether the active keyboard is
 * droidtop's own -- said plainly rather than nagged about. The keyboard's
 * name is the row's value, not part of this line (UI pass 2026-09-24,
 * M14: "Android Keyboard (AOSP) - tap to switch").
 */
private fun keyboardSubtitle(context: android.content.Context): String {
    val current = Keyboards.enabled(context).firstOrNull { it.isCurrent }
    return when {
        current == null -> "Choose which keyboard to use"
        current.isDroidtops -> "Has the full desktop key set. Select to switch"
        // A hint only (docs/SPEC.md 4c): with a companion screen, droidtop's own keyboard is what sends other apps'
        // fields to the companion; droidtop never switches it for the person.
        hasCompanionScreen(context) -> "Choose droidtop's keyboard so other apps type on the companion too"
        else -> "Select to switch keyboards"
    }
}

/** Whether a second screen is connected, where droidtop's companion lives. */
private fun hasCompanionScreen(context: android.content.Context): Boolean =
    (context.getSystemService(android.hardware.display.DisplayManager::class.java)?.displays?.size ?: 1) > 1

/** The companion Home sections that always draw when turned on; every other one hides itself while empty. */
private val ALWAYS_SHOWN_HOME = setOf(CompanionHomeSection.NOTIFICATIONS, CompanionHomeSection.SYSTEM)

/**
 * Interface size for one screen role (owner, 2026-10-29, Droidtop/tracker#656 point 3): Auto, then 75 to 150 percent
 * of the canvas the display's own report gives. The main screen and the companion screen each have one, since they are
 * scaled separately. Six choices, so Left/Right step it in place; the screen redraws at the new size.
 */
private fun interfaceSizeItem(context: Context, role: dev.droidtop.runtime.DisplayRole): ChoiceItem {
    val size = dev.droidtop.runtime.InterfaceSize
    val main = role == dev.droidtop.runtime.DisplayRole.MAIN
    return ChoiceItem(
        id = size.key(role),
        title = if (main) "Interface size, main screen" else "Interface size, companion screen",
        subtitle = words(R.string.gaming_text_and_layout_together_auto_fits),
        options = listOf(ChoiceOption(size.AUTO, "Auto")) + size.PERCENTS.map { ChoiceOption(it.toString(), "$it%") },
        current = size.value(context, role),
        onSelect = { ctx, value -> size.set(ctx, role, value) },
    )
}

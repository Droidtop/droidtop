package dev.droidtop.library.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.droidtop.library.theme.EsDeAspectRatio
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeDownloader
import dev.droidtop.library.theme.ThemePrefs

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
    const val GROUP_OTHER_SHELLS = "other_shells"

    const val ID_GLOBAL_SETTINGS = "pref_global_settings"
    const val ID_DEFAULT_SECTION = "pref_gaming_default_section"
    const val ID_SHOW_HINTS = "pref_gaming_show_hints"
    const val ID_SCRAPER = "pref_gaming_scraper"
    const val ID_SCREENSAVER = "pref_gaming_screensaver"
    const val ID_UI_MODE = "pref_gaming_ui_mode"
    const val ID_CONSOLE_SYSTEMS = "pref_gaming_console_systems"
    const val ID_WINDOWS_GAMES = "pref_gaming_windows_games"
    const val GROUP_SYSTEM = "gaming_system"
    const val ID_SYSTEM_NETWORK = "pref_gaming_system_network"
    const val ID_SYSTEM_VOLUME = "pref_gaming_system_volume"
    const val ID_SYSTEM_BRIGHTNESS = "pref_gaming_system_brightness"
    const val ID_SYSTEM_BRIGHTNESS_GRANT = "pref_gaming_system_brightness_grant"
    const val ID_SYSTEM_BLUETOOTH = "pref_gaming_system_bluetooth"
    const val ID_SYSTEM_VPN = "pref_gaming_system_vpn"
    const val ID_SYSTEM_LEAVE_UI_MODE = "pref_gaming_system_leave_ui_mode"
    const val ID_SYSTEM_DND = "pref_gaming_system_dnd"
    const val ID_SYSTEM_DND_GRANT = "pref_gaming_system_dnd_grant"
    const val ID_SYSTEM_ADAPTIVE = "pref_gaming_system_adaptive"
    const val ID_SYSTEM_ROTATE = "pref_gaming_system_rotate"
    const val ID_SYSTEM_TIMEOUT = "pref_gaming_system_timeout"
    const val ID_SYSTEM_ANDROID_LINKS = "pref_gaming_system_android_links"
    const val ID_SYSTEM_UPDATES = "pref_gaming_system_updates"

    /** Real values the stock Settings app offers, labelled the same way. */
    private val TIMEOUT_OPTIONS = listOf(
        15_000 to "15 seconds",
        30_000 to "30 seconds",
        60_000 to "1 minute",
        120_000 to "2 minutes",
        300_000 to "5 minutes",
        600_000 to "10 minutes",
        1_800_000 to "30 minutes",
    )
    const val ID_GAME_FOLDERS = "pref_gaming_game_folders"
    const val ID_DISPLAY_SHELL_TARGET = "pref_display_shell_target"
    const val ID_DISPLAY_GAME_LAUNCH_TARGET = "pref_display_game_launch_target"
    const val ID_DISPLAY_SWAP = "action_display_swap"
    const val ID_DISPLAY_REINIT = "action_display_reinit"
    const val ID_KEYBOARD_PICK = "action_keyboard_pick"
    const val ID_KEYBOARD_ENABLE = "action_keyboard_enable"
    const val ID_RESCAN_LIBRARY = "pref_gaming_rescan_library"
    const val ID_THEME = "pref_gaming_theme"
    const val ID_THEME_COLOR_SCHEME = "pref_gaming_theme_colorscheme"
    const val ID_THEME_VARIANT = "pref_gaming_theme_variant"
    const val ID_THEME_ASPECT_RATIO = "pref_gaming_theme_aspect_ratio"
    const val ID_SYNC_THEME_INDEX = "pref_gaming_sync_theme_index"
    const val ID_BROWSE_THEMES = "pref_gaming_browse_themes"
    const val ID_APPS_GRID_COLUMNS = "pref_gaming_apps_grid_columns"
    const val ID_DESKTOP_SETTINGS = "pref_gaming_desktop_settings"
    const val ID_STANDARD_SETTINGS = "pref_gaming_standard_settings"

    const val MIN_APPS_GRID_COLUMNS = 2
    const val MAX_APPS_GRID_COLUMNS = 10

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
                SubScreenItem(
                    id = ID_GLOBAL_SETTINGS,
                    title = "Global settings",
                    subtitle = "Home role, default mode, and everything droidtop-wide",
                    fragmentClassName = "app.murinelauncher.settings.SettingsGlobalFragment",
                ),
            ),
        ),
        CatalogGroup(
            id = GROUP_GAMING,
            title = "Shell",
            items = buildList {
                add(defaultSectionItem(context))
                add(showHintsItem(context))
                // Nested catalog screens whose DATA lives in :app -- resolved
                // through SettingsScreenRegistry (registered at process start
                // by :app's SettingsCatalogInitProvider), so they render
                // in-place in whichever surface is showing this catalog
                // instead of bouncing to a differently-chromed activity.
                // Scraper leads, its own row -- real ES-DE keeps the
                // scraper on the MAIN menu (GuiMenu -> GuiScraperMenu),
                // not buried under management screens, and the direction
                // was explicit that droidtop matches that.
                add(
                    ChoiceItem(
                        id = ID_UI_MODE,
                        title = "UI mode",
                        subtitle = "Kiosk hides Settings; Kid also shows only kid-friendly games. " +
                            "Leave either from the Quick Menu's System tab",
                        options = UiMode.entries.map { ChoiceOption(it.name, it.label) },
                        current = UiModePrefs.get(context).name,
                        onSelect = { ctx, value ->
                            UiModeRefresh.set(ctx, runCatching { UiMode.valueOf(value) }.getOrDefault(UiMode.FULL))
                        },
                    ),
                )
                add(
                    ChoiceItem(
                        id = ID_SCREENSAVER,
                        title = "Screensaver",
                        subtitle = "Shows your library's artwork when the shell sits idle",
                        options = listOf(
                            ChoiceOption("OFF", "Off"),
                            ChoiceOption("AFTER_2", "After 2 minutes"),
                            ChoiceOption("AFTER_5", "After 5 minutes"),
                            ChoiceOption("AFTER_10", "After 10 minutes"),
                        ),
                        current = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
                            .getString("droidtop_screensaver_mode", null) ?: "OFF",
                        onSelect = { ctx, value ->
                            ctx.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
                                .edit().putString("droidtop_screensaver_mode", value).apply()
                        },
                    ),
                )
            },
        ),
        CatalogGroup(
            id = GROUP_LIBRARY,
            title = "Library",
            items = buildList {
                add(
                    NestedScreenItem(
                        id = ID_SCRAPER,
                        title = "Scraper",
                        subtitle = "Sources, filters, and content options for artwork and metadata",
                        registryId = "rom_scraper",
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_CONSOLE_SYSTEMS,
                        title = "Console systems",
                        subtitle = "Folders, per-system emulators, artwork scraping, platforms",
                        registryId = "console_systems",
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_GAME_FOLDERS,
                        title = "Game folders",
                        subtitle = "Add or remove the folders droidtop scans for games",
                        registryId = "rom_folders",
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_WINDOWS_GAMES,
                        title = "Windows games",
                        // This is the destination launchWindows's own
                        // "isn't set up yet" error names, so the title
                        // there and here must stay in step.
                        subtitle = "Set up the Wine environment Windows games run inside",
                        registryId = "windows_games",
                    ),
                )
                add(rescanLibraryItem())
            },
        ),
        CatalogGroup(
            id = GROUP_SCREENS,
            title = "Screens",
            items = buildList {
                add(displayShellTargetItem(context))
                add(displayGameLaunchTargetItem(context))
                add(secondScreenRoleItem(context, MODE_GAMING))
                add(secondScreenRoleItem(context, MODE_DESKTOP))
                // Detection can only guess which physical panel is which --
                // Android exposes no position signal -- so the correction
                // is an action, right here in the Quick Menu's System tab,
                // reachable from whichever screen the user is looking at.
                add(
                    AsyncActionItem(
                        id = ID_DISPLAY_SWAP,
                        title = "Swap screens",
                        subtitle = "Move the shell to the other panel when droidtop guessed wrong",
                        run = { ctx, _ -> dev.droidtop.runtime.DisplayArrangement.swap(ctx) },
                    ),
                )
                add(
                    ActionItem(
                        id = ID_DISPLAY_REINIT,
                        title = "Reinitialize displays",
                        subtitle = "Detect connected screens again and re-place the shell",
                        run = { _ -> dev.droidtop.runtime.DisplayArrangement.reinitialize() },
                    ),
                )
            },
        ),
        CatalogGroup(
            id = GROUP_INPUT,
            title = "Input",
            items = buildList {
                // Keyboard. droidtop ships Hacker's Keyboard because a
                // device meant to replace a computer needs Ctrl/Alt/Esc/
                // Tab/arrows/function keys, and it cannot silently set the
                // system input method -- that needs WRITE_SECURE_SETTINGS,
                // which a normal app is not granted. So: say why, then
                // open Android's own pickers.
                add(
                    ActionItem(
                        id = ID_KEYBOARD_PICK,
                        title = "Keyboard",
                        subtitle = keyboardSubtitle(context),
                        run = { ctx -> Keyboards.showPicker(ctx) },
                    ),
                )
                if (!Keyboards.ownKeyboardEnabled(context)) {
                    add(
                        ActionItem(
                            id = ID_KEYBOARD_ENABLE,
                            title = "Turn on Hacker's Keyboard",
                            subtitle = Keyboards.WHY,
                            run = { ctx -> Keyboards.openSystemSettings(ctx) },
                        ),
                    )
                }
            },
        ),
        CatalogGroup(
            id = GROUP_APPEARANCE,
            title = "Appearance",
            items = buildList {
                add(themeItem(context))
                themeColorSchemeItem(context)?.let { add(it) }
                themeVariantItem(context)?.let { add(it) }
                themeAspectRatioItem(context)?.let { add(it) }
                add(
                    AsyncActionItem(
                        id = ID_SYNC_THEME_INDEX,
                        title = "Sync theme index",
                        subtitle = "Update the real ES-DE theme list; run this before Browse themes if that list is empty",
                        run = { ctx, _ ->
                            val result = ThemeDownloader.syncThemesList(ThemeAssets.userThemesDir(ctx))
                            when (result.status) {
                                ThemeDownloader.ThemeSyncStatus.CLONED -> "Theme index downloaded"
                                ThemeDownloader.ThemeSyncStatus.UPDATED -> "Theme index updated"
                                ThemeDownloader.ThemeSyncStatus.UP_TO_DATE -> "Theme index already up to date"
                                ThemeDownloader.ThemeSyncStatus.DIVERGED -> "Theme index has local changes -- skipped"
                                ThemeDownloader.ThemeSyncStatus.FAILED -> "Failed: ${result.error?.message ?: "unknown error"}"
                            }
                        },
                    ),
                )
                add(
                    ActionItem(
                        id = ID_BROWSE_THEMES,
                        title = "Browse themes",
                        subtitle = "Download or update an individual theme from the real ES-DE community index",
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
            },
        ),
        CatalogGroup(
            id = GROUP_SYSTEM,
            title = "System",
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
                val battery = status.batteryPercent
                    ?.let { "$it%" + if (status.charging) ", charging" else "" }
                    ?: "unknown"
                val validation = when {
                    status.network == dev.droidtop.runtime.systemstatus.NetworkKind.NONE -> ""
                    status.validated -> ""
                    // The captive-portal state, said out loud: the row's
                    // action opens the system sheet where signing in
                    // actually happens.
                    else -> " -- connected but NO INTERNET (captive portal?)"
                }
                val vpnLine = if (status.vpnActive) " VPN active." else ""
                add(
                    ActionItem(
                        id = ID_SYSTEM_NETWORK,
                        title = "Network: $network$validation",
                        // The system's own internet panel -- apps lost
                        // programmatic Wi-Fi toggling in API 29, and
                        // opening the real control beats faking one.
                        subtitle = "Battery $battery.$vpnLine Select to open Wi-Fi and data controls",
                        run = { ctx ->
                            ctx.startActivity(controls.internetPanelIntent())
                        },
                    ),
                )
                add(
                    SliderItem(
                        id = ID_SYSTEM_VOLUME,
                        title = "Volume",
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
                            title = "Brightness",
                            min = 0,
                            max = 255,
                            current = controls.brightness(context) ?: 128,
                            onChange = { ctx, value -> controls.setBrightness(ctx, value) },
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
                            title = "Brightness",
                            subtitle = "Opens the system screen where droidtop can be granted Modify system settings",
                            value = "Needs permission",
                            run = { ctx -> ctx.startActivity(controls.brightnessGrantIntent(ctx)) },
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
                            title = "Do Not Disturb",
                            current = controls.dndEnabled(context),
                            onToggle = { ctx, on -> controls.setDnd(ctx, on) },
                        ),
                    )
                } else {
                    add(
                        ActionItem(
                            id = ID_SYSTEM_DND_GRANT,
                            title = "Do Not Disturb",
                            subtitle = "One-time grant on the system screen this opens; afterwards this is a toggle right here",
                            value = "Needs permission",
                            run = { ctx -> ctx.startActivity(controls.dndGrantIntent()) },
                        ),
                    )
                }
                if (controls.canWriteBrightness(context)) {
                    add(
                        ToggleItem(
                            id = ID_SYSTEM_ADAPTIVE,
                            title = "Adaptive brightness",
                            current = controls.adaptiveBrightness(context),
                            onToggle = { ctx, on -> controls.setAdaptiveBrightness(ctx, on) },
                        ),
                    )
                    add(
                        ToggleItem(
                            id = ID_SYSTEM_ROTATE,
                            title = "Auto-rotate",
                            current = controls.autoRotate(context),
                            onToggle = { ctx, on -> controls.setAutoRotate(ctx, on) },
                        ),
                    )
                    add(
                        ChoiceItem(
                            id = ID_SYSTEM_TIMEOUT,
                            title = "Screen timeout",
                            options = TIMEOUT_OPTIONS.map { (ms, label) -> ChoiceOption(ms.toString(), label) },
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
                if (UiModePrefs.get(context).hidesSettings) {
                    add(
                        ActionItem(
                            id = ID_SYSTEM_LEAVE_UI_MODE,
                            title = "Leave ${UiModePrefs.get(context).label}",
                            subtitle = "Restores Settings and the full library",
                            confirmTitle = "Leave restricted mode?",
                            run = { ctx -> UiModeRefresh.set(ctx, UiMode.FULL) },
                        ),
                    )
                }
                add(
                    ActionItem(
                        id = ID_SYSTEM_VPN,
                        title = "VPN",
                        subtitle = "Opens the system VPN screen to connect, disconnect, or configure",
                        value = if (status.vpnActive) "Active" else "Off",
                        run = { ctx ->
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ),
                )
                add(
                    ActionItem(
                        id = ID_SYSTEM_BLUETOOTH,
                        title = "Bluetooth",
                        subtitle = "Pair controllers and audio in the system Bluetooth screen",
                        run = { ctx -> ctx.startActivity(controls.bluetoothSettingsIntent()) },
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_SYSTEM_UPDATES,
                        title = "Software updates",
                        subtitle = "Check for and install newer droidtop builds",
                        // Owned by :app (which this module cannot depend on),
                        // resolved through the registry like android_settings.
                        registryId = "updates",
                    ),
                )
                add(
                    NestedScreenItem(
                        id = ID_SYSTEM_ANDROID_LINKS,
                        title = "Android settings",
                        subtitle = "Every reachable system screen, one hop away -- plus droidtop's own permission grants",
                        registryId = "android_settings",
                    ),
                )
            },
        ),
        CatalogGroup(
            id = GROUP_OTHER_SHELLS,
            title = "Other shells",
            items = listOf(
                SubScreenItem(
                    id = ID_DESKTOP_SETTINGS,
                    title = "Desktop mode",
                    subtitle = "Settings for the Desktop shell",
                    fragmentClassName = "app.murinelauncher.settings.SettingsDesktopFragment",
                ),
                SubScreenItem(
                    id = ID_STANDARD_SETTINGS,
                    title = "Standard mode",
                    subtitle = "General, icons, home screen, and everything else for the Standard shell",
                    fragmentClassName = "app.murinelauncher.settings.SettingsRootFragment",
                ),
            ),
        ),
    )

    private fun defaultSectionItem(context: Context) = ChoiceItem(
        id = ID_DEFAULT_SECTION,
        title = "Default section",
        options = listOf(ChoiceOption("games", "Games"), ChoiceOption("apps", "Apps")),
        current = CatalogPrefs.prefs(context).getString(ID_DEFAULT_SECTION, "games"),
        onSelect = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putString(ID_DEFAULT_SECTION, value).apply()
        },
    )

    private fun showHintsItem(context: Context) = ToggleItem(
        id = ID_SHOW_HINTS,
        title = "Show button hints",
        subtitle = "Show the A/B/Y button legend at the bottom of the Gaming shell",
        current = CatalogPrefs.prefs(context).getBoolean(ID_SHOW_HINTS, true),
        onToggle = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putBoolean(ID_SHOW_HINTS, value).apply()
        },
    )

    private fun displayShellTargetItem(context: Context) = ChoiceItem(
        id = ID_DISPLAY_SHELL_TARGET,
        title = "Gaming shell display",
        options = listOf(
            ChoiceOption("SECOND_WHEN_PRESENT", "Second display when connected"),
            ChoiceOption("BUILT_IN", "Always the built-in screen"),
        ),
        current = CatalogPrefs.prefs(context).getString(ID_DISPLAY_SHELL_TARGET, "SECOND_WHEN_PRESENT"),
        onSelect = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putString(ID_DISPLAY_SHELL_TARGET, value).apply()
        },
    )

    private fun displayGameLaunchTargetItem(context: Context) = ChoiceItem(
        id = ID_DISPLAY_GAME_LAUNCH_TARGET,
        title = "Games launch on",
        options = listOf(
            ChoiceOption("ASK", "Ask every time (default)"),
            ChoiceOption("FOLLOW_SHELL", "Same display as the shell"),
            ChoiceOption("BUILT_IN", "Built-in screen"),
            ChoiceOption("SECOND", "Second display"),
        ),
        current = CatalogPrefs.prefs(context).getString(ID_DISPLAY_GAME_LAUNCH_TARGET, "ASK"),
        onSelect = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putString(ID_DISPLAY_GAME_LAUNCH_TARGET, value).apply()
        },
    )

    /**
     * What the second screen is FOR, per mode (docs/SPEC.md 4 and 6c).
     *
     * Per mode rather than once, because the modes genuinely differ:
     * Desktop's lower screen is an input surface by design, while Gaming
     * moves the shell to the addon and leaves the built-in panel as the
     * ambient widgets surface. Both are the user's to change, which is
     * what section 4 means by the input role being toggleable.
     *
     * Written as raw keys read by `:app`'s `SecondScreenInputPrefs`, the
     * same seam `pref_display_shell_target` already uses: this module must
     * not depend on `:app`.
     */
    private fun secondScreenRoleItem(context: Context, mode: String): ChoiceItem {
        val id = "pref_second_screen_role_$mode"
        val default = if (mode == MODE_DESKTOP) "INPUT" else "COMPANION"
        return ChoiceItem(
            id = id,
            title = if (mode == MODE_DESKTOP) "Second screen in Desktop mode" else "Second screen in Gaming mode",
            options = listOf(
                ChoiceOption("COMPANION", "Widgets and game info"),
                ChoiceOption("INPUT", "Keyboard and trackpad"),
            ),
            current = CatalogPrefs.prefs(context).getString(id, default),
            onSelect = { ctx, value ->
                CatalogPrefs.prefs(ctx).edit().putString(id, value).apply()
            },
        )
    }

    private const val MODE_GAMING = "GAMING"
    private const val MODE_DESKTOP = "DESKTOP"

    private fun themeItem(context: Context): ChoiceItem {
        // The VALUE stays the directory id, which is what ThemePrefs
        // stores; the LABEL is the theme's own name. Settings used to show
        // the id, so the row read "slate-es-de".
        val themes = ThemeAssets.discoverThemes(context)
        return ChoiceItem(
            id = ID_THEME,
            title = "Theme",
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
        return ChoiceItem(
            id = ID_THEME_COLOR_SCHEME,
            title = "Theme color scheme",
            options = schemes.map { ChoiceOption(it, caps.colorSchemeLabels[it] ?: it) },
            current = ThemePrefs.colorScheme(context, themeName) ?: schemes.first(),
            onSelect = { ctx, value -> ThemePrefs.setColorScheme(ctx, themeName, value) },
        )
    }

    private fun themeVariantItem(context: Context): ChoiceItem? {
        val caps = ThemeAssets.activeThemeCapabilities(context) ?: return null
        val themeName = ThemeAssets.activeThemeName(context) ?: return null
        val variants = caps.variants
        if (variants.size <= 1) return null
        return ChoiceItem(
            id = ID_THEME_VARIANT,
            title = "Theme variant",
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
            title = "Theme aspect ratio",
            subtitle = "Automatic follows the screen the app is on",
            options = ratios.map { ChoiceOption(it, EsDeAspectRatio.labelFor(it)) },
            current = ThemePrefs.aspectRatio(context, themeName) ?: ratios.first(),
            onSelect = { ctx, value -> ThemePrefs.setAspectRatio(ctx, themeName, value) },
        )
    }

    private fun appsGridColumnsItem(context: Context) = SliderItem(
        id = ID_APPS_GRID_COLUMNS,
        title = "Apps grid columns",
        subtitle = "Icon density for the Apps tab, independent of the launcher app drawer's own grid width",
        min = MIN_APPS_GRID_COLUMNS,
        max = MAX_APPS_GRID_COLUMNS,
        current = CatalogPrefs.prefs(context).getInt(ID_APPS_GRID_COLUMNS, 5),
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
    fun rescanLibraryItem(): ActionItem = ActionItem(
        id = ID_RESCAN_LIBRARY,
        title = "Rescan library",
        subtitle = "Look for new or changed games and apps again",
        // Default fulfillment: the real deep-link relaunch (the only
        // mechanism available from outside the shell's own composition).
        run = launchComponent(
            "dev.droidtop.app.MainActivity",
            "dev.droidtop.app.EXTRA_MODE" to "gaming",
            "dev.droidtop.app.EXTRA_GAMING_RESCAN" to true,
        ),
    )

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
 * Names the active keyboard, and says plainly when it is not droidtop's
 * own -- rather than nagging, or silently doing nothing about it.
 */
private fun keyboardSubtitle(context: android.content.Context): String {
    val keyboards = Keyboards.enabled(context)
    val current = keyboards.firstOrNull { it.isCurrent }
    return when {
        current == null -> "Choose which keyboard to use"
        current.isDroidtops -> "${current.label} - full desktop key set"
        else -> "${current.label} - tap to switch"
    }
}

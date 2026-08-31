package dev.droidtop.library.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeDownloader
import dev.droidtop.library.theme.ThemePrefs

/**
 * The Handheld mode settings catalog -- the single definition of what
 * Handheld's settings ARE (see SettingsCatalog.kt for the model and the
 * renderer contract). Layout convention shared by every mode's catalog
 * (docs/SPEC.md settings architecture): the droidtop-wide "global" group
 * first (a renderer whose chrome already exposes global settings -- the
 * unified SettingsActivity's persistent action-bar item -- skips it by
 * group id), then the mode's own settings, then shortcuts to the OTHER
 * modes' settings last, so nobody ever has to switch modes just to reach
 * a setting.
 */
object HandheldSettingsCatalog {

    const val GROUP_GLOBAL = "global"
    const val GROUP_HANDHELD = "handheld"
    const val GROUP_OTHER_SHELLS = "other_shells"

    const val ID_GLOBAL_SETTINGS = "pref_global_settings"
    const val ID_DEFAULT_SECTION = "pref_handheld_default_section"
    const val ID_SHOW_HINTS = "pref_handheld_show_hints"
    const val ID_CONSOLE_SYSTEMS = "pref_handheld_console_systems"
    const val ID_WINDOWS_GAMES = "pref_handheld_windows_games"
    const val ID_GAME_FOLDERS = "pref_handheld_game_folders"
    const val ID_DISPLAY_SHELL_TARGET = "pref_display_shell_target"
    const val ID_DISPLAY_GAME_LAUNCH_TARGET = "pref_display_game_launch_target"
    const val ID_RESCAN_LIBRARY = "pref_handheld_rescan_library"
    const val ID_THEME = "pref_handheld_theme"
    const val ID_THEME_COLOR_SCHEME = "pref_handheld_theme_colorscheme"
    const val ID_THEME_VARIANT = "pref_handheld_theme_variant"
    const val ID_SYNC_THEME_INDEX = "pref_handheld_sync_theme_index"
    const val ID_BROWSE_THEMES = "pref_handheld_browse_themes"
    const val ID_APPS_GRID_COLUMNS = "pref_handheld_apps_grid_columns"
    const val ID_DESKTOP_SETTINGS = "pref_handheld_desktop_settings"
    const val ID_STANDARD_SETTINGS = "pref_handheld_standard_settings"

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
            id = GROUP_HANDHELD,
            title = null,
            items = buildList {
                add(defaultSectionItem(context))
                add(showHintsItem(context))
                // Nested catalog screens whose DATA lives in :app -- resolved
                // through SettingsScreenRegistry (registered at process start
                // by :app's SettingsCatalogInitProvider), so they render
                // in-place in whichever surface is showing this catalog
                // instead of bouncing to a differently-chromed activity.
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
                add(displayShellTargetItem(context))
                add(displayGameLaunchTargetItem(context))
                add(
                    ActionItem(
                        id = ID_RESCAN_LIBRARY,
                        title = "Rescan library",
                        subtitle = "Look for new or changed games and apps again",
                        // Default fulfillment: the real deep-link relaunch
                        // (the only mechanism available from outside the
                        // shell's own composition). The in-shell renderer
                        // substitutes its own scan-trigger bump by id.
                        run = launchComponent(
                            "dev.droidtop.app.MainActivity",
                            "dev.droidtop.app.EXTRA_MODE" to "handheld",
                            "dev.droidtop.app.EXTRA_HANDHELD_RESCAN" to true,
                        ),
                    ),
                )
                add(themeItem(context))
                themeColorSchemeItem(context)?.let { add(it) }
                themeVariantItem(context)?.let { add(it) }
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
                            "dev.droidtop.app.EXTRA_MODE" to "handheld",
                            "dev.droidtop.app.EXTRA_HANDHELD_BROWSE_THEMES" to true,
                        ),
                    ),
                )
                add(appsGridColumnsItem(context))
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
        subtitle = "Show the A/B/Y button legend at the bottom of the Handheld shell",
        current = CatalogPrefs.prefs(context).getBoolean(ID_SHOW_HINTS, true),
        onToggle = { ctx, value ->
            CatalogPrefs.prefs(ctx).edit().putBoolean(ID_SHOW_HINTS, value).apply()
        },
    )

    private fun displayShellTargetItem(context: Context) = ChoiceItem(
        id = ID_DISPLAY_SHELL_TARGET,
        title = "Handheld shell display",
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

    private fun themeItem(context: Context): ChoiceItem {
        val themeNames = ThemeAssets.discoverThemes(context).map { it.name }
        return ChoiceItem(
            id = ID_THEME,
            title = "Theme",
            options = themeNames.map { ChoiceOption(it, it) },
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

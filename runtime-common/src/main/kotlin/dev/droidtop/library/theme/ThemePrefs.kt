package dev.droidtop.library.theme

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Real, single selected Handheld theme NAME -- a runtime setting, not a
 * compile-time constant, matching real ES-DE's own actual mechanism
 * (`Settings::getString("Theme")`, confirmed against
 * `es-core/src/ThemeData.cpp`'s `ThemeData::reloadTheme`/`populateThemes`):
 * a plain stored string, looked up by name against whatever themes are
 * actually discovered on disk right now (see [ThemeAssets.discoverThemes]).
 *
 * Deliberately holds NO default/fallback folder name -- real ES-DE's own
 * fallback when the stored name is unset or no longer present is "the
 * first theme alphabetically among what's actually discovered"
 * (`sThemes.begin()`, since `sThemes` is a `std::map` sorted by
 * `StringComparator`), not any single hardcoded theme. [ThemeAssets]
 * applies that same fallback rule -- this object only stores/retrieves
 * the raw preference.
 *
 * Deliberately framework-agnostic (`:runtime-common` has no Compose
 * dependency, and shouldn't gain one just for this) -- a plain
 * SharedPreferences read/write, no reactive/recomposition-trigger state.
 * `:shell-gamepad`'s own `dev.droidtop.shell.gamepad.theme.ThemePrefs` is
 * a thin Compose-reactive wrapper around this same real object (adds a
 * `version` counter Compose can key `remember` blocks off); `:shell-default`
 * (SettingsHandheldFragment's real "Theme" preference) reads/writes this
 * one directly, since AndroidX Preference has no equivalent reactivity
 * need.
 */
object ThemePrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_ACTIVE_THEME = "droidtop_active_theme"

    // Real, confirmed-live bug this fixes: `:shell-default`'s Theme
    // ListPreference writes through THIS object directly (it has no
    // Compose), but the only change signal was shell-gamepad's own
    // wrapper's `version` counter -- which that wrapper only bumped for
    // its OWN set() calls. A theme switched from Settings never reached a
    // running GamepadShell composition at all (confirmed on-device: every
    // switch required a full process restart to take effect). Listeners
    // registered here fire for EVERY real write regardless of which
    // module performed it -- the wrapper registers one that bumps its
    // Compose counter, and ThemeAssets registers one that drops its own
    // parse cache (a stale parse of the previous theme name is harmless,
    // but a re-downloaded/updated theme under the SAME name must not keep
    // serving its old parse).
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    /** For non-selection changes that still invalidate theme state (e.g. a theme re-downloaded in place) -- fires the same listeners a real selection change does. */
    fun notifyThemesChanged() {
        changeListeners.forEach { it() }
    }

    /** Null means "no explicit selection" -- caller falls back per real ES-DE's own rule. */
    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ACTIVE_THEME, null)

    fun set(context: Context, themeName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_THEME, themeName).apply()
        changeListeners.forEach { it() }
    }

    // Real ES-DE parity: colorScheme and variant are USER SETTINGS
    // (`Settings::getString("ThemeColorScheme")`/`("ThemeVariant")`, the
    // UI-Settings > Theme menus), not "whatever capabilities.xml happens
    // to declare first" -- the first-declared entry is only the default.
    // Confirmed to matter live: DEcaffe declares colorScheme "5"
    // (Blue dark) first, so droidtop always rendered Blue dark while the
    // theme's own showcase screenshots use "3" (Hyrule) -- the whole
    // palette difference, not a rendering bug. Stored PER THEME (keys
    // suffixed by theme name): scheme/variant names are meaningless
    // across themes.

    fun colorScheme(context: Context, themeName: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("droidtop_theme_colorscheme::$themeName", null)

    fun setColorScheme(context: Context, themeName: String, scheme: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .apply { if (scheme == null) remove("droidtop_theme_colorscheme::$themeName") else putString("droidtop_theme_colorscheme::$themeName", scheme) }
            .apply()
        changeListeners.forEach { it() }
    }

    fun variant(context: Context, themeName: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("droidtop_theme_variant::$themeName", null)

    fun setVariant(context: Context, themeName: String, variant: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .apply { if (variant == null) remove("droidtop_theme_variant::$themeName") else putString("droidtop_theme_variant::$themeName", variant) }
            .apply()
        changeListeners.forEach { it() }
    }
}

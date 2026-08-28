package dev.droidtop.shell.gamepad.theme

import android.content.Context

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
 */
object ThemePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_ACTIVE_THEME = "droidtop_active_theme"

    /** Null means "no explicit selection" -- caller falls back per real ES-DE's own rule. */
    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ACTIVE_THEME, null)

    fun set(context: Context, themeName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_THEME, themeName).apply()
    }
}

package dev.droidtop.shell.gamepad.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.droidtop.library.theme.ThemePrefs as LibraryThemePrefs

/**
 * Thin Compose-reactive wrapper around the real, framework-agnostic
 * `dev.droidtop.library.theme.ThemePrefs` (`:library-core` -- also read/
 * written directly by `:shell-default`'s SettingsHandheldFragment, which
 * has no Compose to react with). This object exists ONLY to add [version]:
 * `ThemeAssets.loadActiveTheme`'s own callers key their `remember` blocks
 * off it (alongside the system id they already key off), so changing the
 * active theme from Settings actually re-renders Games/Apps instead of
 * silently doing nothing until some unrelated recomposition happens to
 * occur. A plain SharedPreferences write has no such signal on its own --
 * Compose only recomposes off state it's actually reading.
 */
object ThemePrefs {
    var version by mutableIntStateOf(0)
        private set

    init {
        // Real, confirmed-live fix (see the library object's own doc
        // comment): the ONLY writer that matters in practice is
        // :shell-default's Theme ListPreference, which writes through the
        // library object directly and so never bumped this counter --
        // switching themes from Settings did nothing to a running shell
        // until a full process restart. Registering here means every real
        // write, from any module, bumps the Compose signal. Registration
        // happens on first touch of this object, which is guaranteed
        // before any composition could possibly need the signal (the
        // compositions that react to [version] are the same ones reading
        // it).
        LibraryThemePrefs.addOnChangeListener { version++ }
    }

    fun get(context: Context): String? = LibraryThemePrefs.get(context)

    fun set(context: Context, themeName: String) {
        // The library object's own listeners handle the version bump now.
        LibraryThemePrefs.set(context, themeName)
    }
}

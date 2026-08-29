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

    fun get(context: Context): String? = LibraryThemePrefs.get(context)

    fun set(context: Context, themeName: String) {
        LibraryThemePrefs.set(context, themeName)
        version++
    }
}

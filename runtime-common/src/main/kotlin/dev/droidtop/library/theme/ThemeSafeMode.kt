package dev.droidtop.library.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Crash-loop safe mode for the Gaming shell (docs/SPEC.md 10c): while on,
 * [ThemeAssets] resolves no active theme, which is the state the shell
 * already draws its unthemed fallback surface for. The stored theme choice
 * is untouched; turning this off draws the theme again. Set by the crash
 * recovery at process start, read by the shell's banner.
 */
object ThemeSafeMode {
    private val state = MutableStateFlow(false)

    val activeFlow: StateFlow<Boolean> get() = state

    val active: Boolean get() = state.value

    fun set(active: Boolean) {
        if (state.value == active) return
        state.value = active
        // The same signal a theme change gives: the parse cache drops and the shell recomposes.
        ThemePrefs.notifyThemesChanged()
    }
}

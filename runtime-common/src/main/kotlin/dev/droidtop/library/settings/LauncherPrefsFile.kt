package dev.droidtop.library.settings

/**
 * Name of the one SharedPreferences file every droidtop module reads and
 * writes its own settings/state into (`com.android.launcher3.LauncherFiles.
 * SHARED_PREFERENCES_KEY`, the shared prefs file the vendored Launcher3
 * fork under `:shell-default` uses for its own settings).
 *
 * Every module outside `:shell-default` reads this by the literal string
 * rather than depending on `:shell-default` (a real, heavy Android module)
 * just to read one filename off `com.android.launcher3.LauncherFiles`.
 * `:shell-default` itself can and does reference `LauncherFiles.
 * SHARED_PREFERENCES_KEY` directly where convenient, but this constant
 * exists so every *other* module shares one definition of that literal
 * instead of re-typing (and re-explaining) it in each file that needs it —
 * every module already depends on `:runtime-common`.
 */
const val LAUNCHER_PREFS_FILE_NAME = "com.android.launcher3.prefs"

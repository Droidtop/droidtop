package dev.droidtop.library

import android.content.Context
import java.io.File
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Real, current ROM/game root folders -- reads the same
 * LAUNCHER_PREFS_FILE_NAME SharedPreferences key `:app`'s own
 * `GamesRootPrefs` (dev.droidtop.app.OnboardingPrefs) writes, by literal
 * name rather than a compile-time dependency -- :library-core can't
 * depend on :app (:app depends on it), same established pattern
 * shell-gamepad's own HandheldPrefs doc comment documents for the same
 * reason.
 *
 * Read fresh on every call, not cached or passed in frozen at
 * construction time. Real gap this fixes: [ConsoleRomProvider]/
 * [EngineGameProvider] used to be built once in MainActivity.onCreate
 * with a snapshot `List<File>`, so adding or removing a ROM folder at
 * runtime (see the "ROM folders" Settings screen) silently did nothing
 * until the whole app restarted.
 */
object GamesRoots {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_GAMES_ROOT_PATHS = "droidtop_games_root_paths"

    /** Same real fallback MainActivity's own onCreate used to apply itself: an app-private default for a fresh install that hasn't been through onboarding (or has zero roots configured) yet. */
    fun current(context: Context): List<File> {
        val configured = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_GAMES_ROOT_PATHS, emptySet())
            ?.map(::File)
            .orEmpty()
        return configured.ifEmpty {
            listOf(File(context.getExternalFilesDir(null), "games").apply { mkdirs() })
        }
    }
}

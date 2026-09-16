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
 * shell-gamepad's own GamingPrefs doc comment documents for the same
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

    private const val KEY_SCANNED_ROOTS = "droidtop_games_roots_last_scanned"

    /**
     * Whether the set of roots has changed since the last scan that was
     * told about it -- in which case an ordinary scan has to re-walk
     * instead of serving what a provider cached.
     *
     * The rig showed why this is not optional. On a fresh install the
     * shell composes once with no roots, every provider caches "nothing",
     * and onboarding then adds the games folder. The next ordinary scan
     * trusted those cached rows, so first run ended on "No games detected
     * yet." with 152 games in the folder the user had just named and no
     * hint that Settings > Game folders > Rescan library was the way out.
     * Changing which folders are scanned is exactly the event a cache of
     * what is in them cannot survive.
     */
    fun rootsChangedSinceLastScan(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SCANNED_ROOTS, null) != signature(context)
    }

    /** Records that a scan has covered the roots as they are now. */
    fun markScanned(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCANNED_ROOTS, signature(context))
            .apply()
    }

    private fun signature(context: Context): String =
        current(context).map { it.absolutePath }.sorted().joinToString(File.pathSeparator)
}

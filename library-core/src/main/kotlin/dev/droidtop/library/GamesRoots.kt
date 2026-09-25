package dev.droidtop.library

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

    /**
     * Emits once immediately and again every time the configured roots
     * change, so "the roots changed, walk them" is ONE mechanism with one
     * subscriber rather than a check each screen happens to run at a
     * moment of its own choosing.
     *
     * The rig showed why a one-shot check at composition is not enough
     * (build 539): onboarding runs as an Activity stacked ON TOP of the
     * shell and adds the games folder while the shell's composition is
     * alive, and finishing it resumes the shell through `onResume`, not a
     * recomposition -- so the shell's own "have the roots changed?" check
     * had already run, against no roots, and never ran again. The library
     * stayed on "No games detected yet." with 151 games in the folder the
     * person had just named, and only the Settings rescan intent could
     * start a walk. A preference listener sees the write itself, whatever
     * the screen on top is doing.
     */
    fun changes(context: Context): Flow<Unit> = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // A null key is a clear() of the whole file, which changes the
            // roots too.
            if (key == null || key == KEY_GAMES_ROOT_PATHS) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
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

/**
 * Keeps [kinds] scanned against the games roots as they are now, for as
 * long as the caller is collecting (docs/SPEC.md 7g): every surface that
 * lists games runs this one loop rather than its own copy of it.
 *
 * A changed root set invalidates what the providers cached about the old
 * one, so the first scan after a folder is added is a real walk rather
 * than a replay of an empty index, and a walk already in flight -- which
 * is walking the old folders -- is restarted. A root the user took away
 * takes its games with it, before the walk, so the list never shows a
 * removed root's games while the new set is read. Collected rather than
 * checked once: onboarding and Settings add a folder while the surface's
 * composition is alive and hand back through onResume, which recomposes
 * nothing. The Launcher's Games grid used to ask for one plain scan
 * instead, joined whatever walk was already running over the old roots,
 * and stayed on "No games yet" until Gaming had run this loop (rig, build
 * 814).
 */
suspend fun Library.scanFollowingGamesRoots(
    context: Context,
    kinds: Set<LibraryEntryKind>,
    rescan: Boolean = false,
) {
    GamesRoots.changes(context).collect {
        val rootsChanged = GamesRoots.rootsChangedSinceLastScan(context)
        if (rootsChanged) {
            keepOnlyRoots(GamesRoots.current(context).map { root -> root.absolutePath }.toSet())
        }
        scanInBackground(kinds, rescan = rescan || rootsChanged, restart = rootsChanged)
        if (rootsChanged) GamesRoots.markScanned(context)
    }
}

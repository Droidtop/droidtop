package com.android.launcher3.allapps

import android.content.ComponentName
import android.content.Context
import com.android.launcher3.util.Executors
import dev.droidtop.runtime.CachedPref
import dev.droidtop.shell.standard.LauncherPrefsFile

/**
 * droidtop patch (not upstream Launcher3): backs the app drawer's own
 * "Recent" row (docs/SPEC.md, Launcher mode survey, "Recent/frequently-
 * used apps row" -- confirmed genuinely absent from this Murine fork, no
 * `PredictedAppIcon`/`UsageStatsManager` plumbing existed anywhere in
 * `:shell-default`). Deliberately not built on `UsageStatsManager`:
 * that needs the special "Usage access" grant (a Settings toggle, not a
 * runtime permission dialog), which a fresh install would not have and
 * this pass has no UI to request. Instead, a launch is recorded directly
 * at the one place both workspace and drawer icon taps already funnel
 * through (`ItemClickHandler.startAppShortcutOrInfoActivity`) -- the same
 * "droidtop tracks its own history" shape `RoomPlayHistoryStore` already
 * uses for games (docs/SPEC.md 7g), just SharedPreferences-simple since
 * this is component names, not game records.
 */
object RecentAppsStore {
    private const val KEY_RECENT_COMPONENTS = "droidtop_recent_app_components"
    private const val KEY_LAUNCH_COUNTS = "droidtop_app_launch_counts"
    private const val SEPARATOR = "|"

    /** How many apps' launch counts are kept; the least launched go first past it. */
    private const val MAX_COUNTED = 300

    // Bounded so the stored string never grows without limit; comfortably
    // more than a drawer row will ever display (see RECENT_ROW_LIMIT).
    private const val MAX_TRACKED = 30

    private val recent = CachedPref<List<ComponentName>>(
        KEY_RECENT_COMPONENTS, emptyList(),
        { raw -> raw.split(SEPARATOR).filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it) } },
        { list -> list.joinToString(SEPARATOR) { it.flattenToString() } },
    )

    private val counts = CachedPref<Map<String, Int>>(
        KEY_LAUNCH_COUNTS, emptyMap(),
        { raw ->
            raw.split(SEPARATOR).mapNotNull { entry ->
                val name = entry.substringBeforeLast('=', "")
                val count = entry.substringAfterLast('=', "").toIntOrNull()
                if (name.isEmpty() || count == null) null else name to count
            }.toMap()
        },
        { map -> map.entries.joinToString(SEPARATOR) { "${it.key}=${it.value}" } },
    )

    /**
     * Records [component] as just launched: most-recent-first, deduplicated. Called on an icon tap, so the lists are
     * changed and written on the module's executor, not the main thread (Droidtop/tracker#771).
     */
    @JvmStatic
    fun recordLaunch(context: Context, component: ComponentName) {
        val prefs = LauncherPrefsFile(context)
        Executors.THREAD_POOL_EXECUTOR.execute {
            recent.update(prefs) { list -> (listOf(component) + (list - component)).take(MAX_TRACKED) }
            counts.update(prefs) { map ->
                val name = component.flattenToString()
                val next = map + (name to (map[name] ?: 0) + 1)
                if (next.size <= MAX_COUNTED) next else next.entries.sortedByDescending { it.value }.take(MAX_COUNTED).associate { it.toPair() }
            }
        }
    }

    /**
     * How often each app was launched from the launcher, by flattened component name: the drawer's Most used sort
     * (`LauncherPrefs.DRAWER_SORT`, Droidtop/tracker#564). Counted from the first launch after this was built; an
     * app never launched since is absent.
     */
    @JvmStatic
    fun launchCounts(context: Context): Map<String, Int> = counts.get(LauncherPrefsFile(context))

    /** Whether a preferences change is to this list, for a surface that follows it (the companion's Recent apps). */
    @JvmStatic
    fun isRecentKey(key: String?): Boolean = key == KEY_RECENT_COMPONENTS

    /** Every tracked component, most-recent-first. */
    @JvmStatic
    fun current(context: Context): List<ComponentName> = recent.get(LauncherPrefsFile(context))
}

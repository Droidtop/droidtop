package com.android.launcher3.allapps

import android.content.ComponentName
import android.content.Context
import com.android.launcher3.LauncherFiles

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

    /** Records [component] as just launched: most-recent-first, deduplicated. */
    @JvmStatic
    fun recordLaunch(context: Context, component: ComponentName) {
        val existing = current(context).toMutableList()
        existing.remove(component)
        existing.add(0, component)
        while (existing.size > MAX_TRACKED) existing.removeAt(existing.size - 1)
        val counts = launchCounts(context).toMutableMap()
        counts[component.flattenToString()] = (counts[component.flattenToString()] ?: 0) + 1
        val kept = counts.entries.sortedByDescending { it.value }.take(MAX_COUNTED)
        context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_COMPONENTS, existing.joinToString(SEPARATOR) { it.flattenToString() })
            .putString(KEY_LAUNCH_COUNTS, kept.joinToString(SEPARATOR) { "${it.key}=${it.value}" })
            .apply()
    }

    /**
     * How often each app was launched from the launcher, by flattened component name: the drawer's Most used sort
     * (`LauncherPrefs.DRAWER_SORT`, Droidtop/tracker#564). Counted from the first launch after this was built; an
     * app never launched since is absent.
     */
    @JvmStatic
    fun launchCounts(context: Context): Map<String, Int> {
        val raw = context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .getString(KEY_LAUNCH_COUNTS, null) ?: return emptyMap()
        return raw.split(SEPARATOR).mapNotNull { entry ->
            val name = entry.substringBeforeLast('=', "")
            val count = entry.substringAfterLast('=', "").toIntOrNull()
            if (name.isEmpty() || count == null) null else name to count
        }.toMap()
    }

    /** Whether a preferences change is to this list, for a surface that follows it (the companion's Recent apps). */
    @JvmStatic
    fun isRecentKey(key: String?): Boolean = key == KEY_RECENT_COMPONENTS

    /** Every tracked component, most-recent-first. */
    @JvmStatic
    fun current(context: Context): List<ComponentName> {
        val raw = context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_COMPONENTS, null) ?: return emptyList()
        return raw.split(SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
    }
}

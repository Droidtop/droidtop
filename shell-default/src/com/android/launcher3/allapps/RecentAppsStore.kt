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
    private const val SEPARATOR = "|"

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
        context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_COMPONENTS, existing.joinToString(SEPARATOR) { it.flattenToString() })
            .apply()
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

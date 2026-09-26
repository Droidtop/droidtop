package com.android.launcher3.touch

import android.content.Context
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherPrefs
import app.murinelauncher.settings.SettingsHomeFragment

/**
 * droidtop patch: carries the old two-boolean gesture prefs (double-tap
 * to sleep, swipe-down for notifications) over to the new per-slot
 * [GestureAction] once, for an install that already has them set, so
 * "assignable gesture actions" (docs/SPEC.md, Launcher mode survey)
 * lands without silently changing what an existing install's gestures do.
 * A fresh install never had the old keys, so it just gets
 * [LauncherPrefs.GESTURE_DOUBLE_TAP_ACTION]/[LauncherPrefs.GESTURE_SWIPE_DOWN_ACTION]'s
 * own defaults, which already match the old booleans' own defaults.
 * Reads the raw `SharedPreferences` directly (not through the [LauncherPrefs]
 * `Item` system) only to check whether the old keys were ever written at
 * all -- `contains`, not `get`, is the point.
 */
object GestureActionMigration {
    private const val MIGRATED_KEY = "droidtop_gesture_actions_migrated"

    @JvmStatic
    fun applyOnce(context: Context) {
        val sp = context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        if (sp.getBoolean(MIGRATED_KEY, false)) return

        val prefs = LauncherPrefs.get(context)
        if (sp.contains(SettingsHomeFragment.DOUBLE_TAP_TO_SLEEP)) {
            val wasOn = sp.getBoolean(SettingsHomeFragment.DOUBLE_TAP_TO_SLEEP, false)
            prefs.put(
                LauncherPrefs.GESTURE_DOUBLE_TAP_ACTION,
                if (wasOn) GestureAction.LOCK_SCREEN else GestureAction.NONE,
            )
        }
        if (sp.contains(SettingsHomeFragment.SWIPE_DOWN_NOTIFICATIONS)) {
            val wasOn = sp.getBoolean(SettingsHomeFragment.SWIPE_DOWN_NOTIFICATIONS, true)
            prefs.put(
                LauncherPrefs.GESTURE_SWIPE_DOWN_ACTION,
                if (wasOn) GestureAction.OPEN_NOTIFICATIONS else GestureAction.NONE,
            )
        }
        sp.edit().putBoolean(MIGRATED_KEY, true).apply()
    }
}

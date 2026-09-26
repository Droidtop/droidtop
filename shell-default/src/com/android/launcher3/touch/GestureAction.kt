package com.android.launcher3.touch

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import app.murinelauncher.receiver.ScreenOffAdminReceiver
import app.murinelauncher.service.MurineAccessibilityService
import app.murinelauncher.settings.SettingsHomeFragment
import app.murinelauncher.widget.accessibility.AlertDialogSheet
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities

/**
 * droidtop patch (not upstream Murine/Launcher3): what a gesture slot
 * (double-tap, swipe-down) does, assignable per slot instead of each
 * gesture hardcoding one fixed action -- Nova/Apex's "map any gesture to
 * any action" (docs/SPEC.md, Launcher mode survey, "Assignable gesture
 * actions"). Scoped to actions that already have a real handler in this
 * fork: launching an app or a specific shortcut is not offered yet (no
 * picker UI exists to name one), so it is left out rather than faked.
 */
enum class GestureAction(val displayNameRes: Int, val summaryRes: Int) {
    NONE(R.string.gesture_action_none, R.string.gesture_action_none_summary),
    LOCK_SCREEN(R.string.gesture_action_lock_screen, R.string.gesture_action_lock_screen_summary),
    OPEN_NOTIFICATIONS(
        R.string.gesture_action_open_notifications,
        R.string.gesture_action_open_notifications_summary,
    ),
    OPEN_DRAWER(R.string.gesture_action_open_drawer, R.string.gesture_action_open_drawer_summary),
    ;

    fun getDisplayName(context: Context): String = context.getString(displayNameRes)
    fun getSummary(context: Context): String = context.getString(summaryRes)

    /** Runs the action. Returns whether the gesture that triggered it should be consumed. */
    fun perform(launcher: Launcher): Boolean = when (this) {
        NONE -> false
        LOCK_SCREEN -> {
            lockScreen(launcher)
            true
        }
        OPEN_NOTIFICATIONS -> {
            expandNotifications(launcher)
            true
        }
        OPEN_DRAWER -> {
            launcher.stateManager.goToState(LauncherState.ALL_APPS, true /* animated */)
            true
        }
    }

    companion object {
        /**
         * Moved here from `WorkspaceTouchListener`'s own `onDoubleTap`
         * (this was the only caller): the accessibility-service lock path
         * Android P+ requires, with the legacy device-admin fallback below
         * it for older devices. Behaviour unchanged, just callable from
         * any gesture slot rather than only the double-tap one.
         */
        private fun lockScreen(launcher: Launcher) {
            if (Utilities.ATLEAST_P) {
                val accessibility = MurineAccessibilityService.INSTANCE
                if (accessibility != null) {
                    accessibility.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                } else if (!LauncherPrefs.ACCESSIBILITY_DISCLOSURE_ACCEPTED.get(launcher)) {
                    AlertDialogSheet.show(
                        launcher,
                        launcher.getString(R.string.pref_accessibility_disclosure_title),
                        launcher.getString(R.string.pref_accessibility_disclosure_desc),
                    ) {
                        LauncherPrefs.get(launcher).put(LauncherPrefs.ACCESSIBILITY_DISCLOSURE_ACCEPTED, true)
                        SettingsHomeFragment.requestAccessibilityPermission(launcher)
                    }
                } else {
                    SettingsHomeFragment.requestAccessibilityPermission(launcher)
                }
            } else {
                lockScreenLegacy(launcher)
            }
        }

        private fun lockScreenLegacy(launcher: Launcher) {
            val dpm = launcher.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = ComponentName(launcher, ScreenOffAdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(admin)) {
                dpm.lockNow()
            }
        }

        /**
         * Moved here from `NotificationSwipeController`'s own private
         * method (this was the only caller): same reflection-based
         * `expandNotificationsPanel` call, callable from any gesture slot.
         */
        private fun expandNotifications(launcher: Launcher) {
            try {
                val sbService = launcher.getSystemService("statusbar")
                if (sbService != null) {
                    sbService.javaClass.getMethod("expandNotificationsPanel").invoke(sbService)
                }
            } catch (_: Exception) {
                // Best-effort, matching the previous behaviour exactly.
            }
        }
    }
}

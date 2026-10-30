package com.android.launcher3.touch

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import app.murinelauncher.receiver.ScreenOffAdminReceiver
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
 * actions"). [LAUNCH_APP] starts an app the person picked, kept beside the
 * slot's own preference and handed to [perform]; dev.droidtop.shell.standard.HomeActions
 * runs every slot.
 *
 * [id] is what a saved action holds (an icon's swipe, `HomeAction.Droidtop`, and the
 * Nova and Apex import's `ImportActions`): written out per constant, so renaming a
 * constant never turns a saved action into nothing (Droidtop/tracker#771).
 * The slots are double-tap, swipe down, two-finger swipe down and up,
 * pinch in and out ([MultiFingerGestureController]), and the Home button
 * pressed while already on the first page.
 */
enum class GestureAction(val id: String, val displayNameRes: Int, val summaryRes: Int) {
    NONE("NONE", R.string.gesture_action_none, R.string.gesture_action_none_summary),
    LOCK_SCREEN("LOCK_SCREEN", R.string.gesture_action_lock_screen, R.string.gesture_action_lock_screen_summary),
    OPEN_NOTIFICATIONS(
        "OPEN_NOTIFICATIONS",
        R.string.gesture_action_open_notifications,
        R.string.gesture_action_open_notifications_summary,
    ),
    OPEN_DRAWER("OPEN_DRAWER", R.string.gesture_action_open_drawer, R.string.gesture_action_open_drawer_summary),

    /**
     * The system Recents (task switcher). This build compiles no quickstep module, so the
     * launcher has no Overview of its own; the OS's recents provider answers the Recents key
     * and gesture, and this slot asks it the same thing through the accessibility service's
     * global action (docs/SPEC.md, Standard mode "Recents").
     */
    OPEN_RECENTS("OPEN_RECENTS", R.string.gesture_action_open_recents, R.string.gesture_action_open_recents_summary),

    /** The task manager's Clear all apps (docs/SPEC.md "The task manager"): it asks first when more than a few would close. */
    CLEAR_ALL_APPS("CLEAR_ALL_APPS", R.string.gesture_action_clear_all_apps, R.string.gesture_action_clear_all_apps_summary),

    /** droidtop's companion as a pull-down sheet, for a device with one screen (docs/SPEC.md "The companion's tabs", One screen). */
    OPEN_COMPANION("OPEN_COMPANION", R.string.gesture_action_open_companion, R.string.gesture_action_open_companion_summary),

    /** droidtop's one search over apps, the library and the download sources (docs/SPEC.md 12a "Launcher search"). */
    OPEN_SEARCH("OPEN_SEARCH", R.string.gesture_action_open_search, R.string.gesture_action_open_search_summary),

    /** The Quick Menu, the sheet Gaming's R2 and Desktop's tray open (`dev.droidtop.app.QuickMenuActivity`). */
    OPEN_QUICK_MENU("OPEN_QUICK_MENU", R.string.gesture_action_open_quick_menu, R.string.gesture_action_open_quick_menu_summary),

    /** The mode switcher, as the Back button's menu opens it. */
    SWITCH_MODE("SWITCH_MODE", R.string.gesture_action_switch_mode, R.string.gesture_action_switch_mode_summary),

    /** The library's Games grid, as the home screen's menu opens it. */
    OPEN_GAMES("OPEN_GAMES", R.string.gesture_action_open_games, R.string.gesture_action_open_games_summary),

    /** An app the person picked (Nova's and Apex's "open an app" gesture; Droidtop/tracker#564). */
    LAUNCH_APP("LAUNCH_APP", R.string.gesture_action_launch_app, R.string.gesture_action_launch_app_summary),
    ;

    fun getDisplayName(context: Context): String = context.getString(displayNameRes)
    fun getSummary(context: Context): String = context.getString(summaryRes)

    /**
     * Runs the action; [app] is the component [LAUNCH_APP] starts (none: it does nothing). Returns whether the gesture
     * that triggered it should be consumed.
     */
    fun perform(launcher: Launcher, app: String? = null): Boolean = when (this) {
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
        OPEN_RECENTS -> {
            performGlobal(launcher, AccessibilityService.GLOBAL_ACTION_RECENTS)
            true
        }
        CLEAR_ALL_APPS -> {
            dev.droidtop.shell.standard.ClearAllApps.open(launcher)
            true
        }
        OPEN_COMPANION -> {
            dev.droidtop.runtime.CompanionSheet.open(launcher, false)
            true
        }
        OPEN_SEARCH -> {
            dev.droidtop.shell.standard.LauncherSearch.open(launcher)
            true
        }
        OPEN_QUICK_MENU -> {
            dev.droidtop.shell.standard.DroidtopScreens.openQuickMenu(launcher)
            true
        }
        SWITCH_MODE -> {
            dev.droidtop.shell.standard.ModeSwitcher.open(launcher)
            true
        }
        OPEN_GAMES -> {
            dev.droidtop.shell.standard.DroidtopScreens.openGames(launcher)
            true
        }
        LAUNCH_APP -> app != null && dev.droidtop.shell.standard.HomeActions.launchApp(launcher, app)
    }

    companion object {
        /** The action a saved [id] names; null for one this build does not have. */
        @JvmStatic
        fun byId(id: String): GestureAction? = entries.firstOrNull { it.id == id }

        /** Android P and later lock through the accessibility service; older devices through the device admin. */
        private fun lockScreen(launcher: Launcher) {
            if (Utilities.ATLEAST_P) {
                performGlobal(launcher, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                lockScreenLegacy(launcher)
            }
        }

        /**
         * One path for every action that only the accessibility service may perform: run it if
         * the service is connected, otherwise show the disclosure once and then the system's
         * accessibility settings.
         */
        private fun performGlobal(launcher: Launcher, action: Int) {
            if (dev.droidtop.runtime.keyboard.AccessibilityHost.performGlobalAction(action)) {
                // Done by droidtop's one accessibility service.
            } else if (!LauncherPrefs.ACCESSIBILITY_DISCLOSURE_ACCEPTED.get(launcher)) {
                AlertDialogSheet.show(
                    launcher,
                    launcher.getString(R.string.pref_accessibility_disclosure_title),
                    launcher.getString(R.string.pref_accessibility_disclosure_desc),
                ) {
                    LauncherPrefs.get(launcher).put(LauncherPrefs.ACCESSIBILITY_DISCLOSURE_ACCEPTED, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) SettingsHomeFragment.requestAccessibilityPermission(launcher)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) SettingsHomeFragment.requestAccessibilityPermission(launcher)
            }
        }

        private fun lockScreenLegacy(launcher: Launcher) {
            val dpm = launcher.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = ComponentName(launcher, ScreenOffAdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(admin)) {
                dpm.lockNow()
            }
        }

        /** The notification shade, through the status bar service's `expandNotificationsPanel` (not public API). */
        private fun expandNotifications(launcher: Launcher) {
            try {
                val sbService = launcher.getSystemService("statusbar")
                if (sbService != null) {
                    sbService.javaClass.getMethod("expandNotificationsPanel").invoke(sbService)
                }
            } catch (e: Exception) {
                android.util.Log.w("droidtop.GestureAction", "could not open the notifications", e)
            }
        }
    }
}

/**
 * Standard's gesture slots, listed once (Droidtop/tracker#771): each slot's name, which is also its name in the Nova
 * and Apex import (`ImportActions`) and in [dev.droidtop.runtime.StandardGestures.Slot]; the key of its row in Home
 * settings; and the preference that holds its action. The importer, the multi-finger controller and the settings read
 * this table, so a new slot is one row here.
 */
enum class GestureSlot(val settingKey: String) {
    DOUBLE_TAP(SettingsHomeFragment.DOUBLE_TAP_ACTION),
    SWIPE_DOWN(SettingsHomeFragment.SWIPE_DOWN_ACTION),
    TWO_FINGER_DOWN(SettingsHomeFragment.TWO_FINGER_DOWN_ACTION),
    TWO_FINGER_UP(SettingsHomeFragment.TWO_FINGER_UP_ACTION),
    PINCH_IN(SettingsHomeFragment.PINCH_IN_ACTION),
    PINCH_OUT(SettingsHomeFragment.PINCH_OUT_ACTION),
    HOME_BUTTON(SettingsHomeFragment.HOME_BUTTON_ACTION),
    ;

    val pref: com.android.launcher3.ConstantItem<GestureAction>
        get() = when (this) {
            DOUBLE_TAP -> LauncherPrefs.GESTURE_DOUBLE_TAP_ACTION
            SWIPE_DOWN -> LauncherPrefs.GESTURE_SWIPE_DOWN_ACTION
            TWO_FINGER_DOWN -> LauncherPrefs.GESTURE_TWO_FINGER_DOWN_ACTION
            TWO_FINGER_UP -> LauncherPrefs.GESTURE_TWO_FINGER_UP_ACTION
            PINCH_IN -> LauncherPrefs.GESTURE_PINCH_IN_ACTION
            PINCH_OUT -> LauncherPrefs.GESTURE_PINCH_OUT_ACTION
            HOME_BUTTON -> LauncherPrefs.GESTURE_HOME_BUTTON_ACTION
        }

    companion object {
        fun byName(name: String): GestureSlot? = entries.firstOrNull { it.name == name }

        fun bySettingKey(key: String?): GestureSlot? = entries.firstOrNull { it.settingKey == key }

        /** The two-finger slot [StandardGestures][dev.droidtop.runtime.StandardGestures] recognised. */
        fun of(slot: dev.droidtop.runtime.StandardGestures.Slot): GestureSlot = valueOf(slot.name)
    }
}

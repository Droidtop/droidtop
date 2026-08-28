package dev.droidtop.shell.standard

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * droidtop's shell switcher — Android, Desktop, Handheld, or Settings.
 * Shown from a long-press of the back key (`Activity.onKeyLongPress(
 * KeyEvent.KEYCODE_BACK, ...)`), not a plain back press: a plain press must
 * keep doing its normal job (closing all-apps/a folder in Standard,
 * whatever the current shell's own back handling does) in every shell, not
 * just when nothing else is open. Wired into both
 * com.android.launcher3.Launcher (this module) and dev.droidtop.app.
 * MainActivity (:app, which hosts the Desktop/Handheld shells) so the same
 * menu is reachable from anywhere, not just the home screen.
 *
 * Note: long-press-of-back reliably fires on hardware back keys and
 * 3-button navigation (the standard Android onKeyLongPress mechanism); on
 * gesture navigation, whether a held back-swipe reaches onKeyLongPress at
 * all is OS-version/OEM-dependent and hasn't been verified against a real
 * device here — worth confirming on the actual Retroid Pocket 5 rather than
 * assumed to definitely work.
 */
object BackButtonMenu {
    private const val APP_MAIN_ACTIVITY = "dev.droidtop.app.MainActivity"
    private const val STANDARD_LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"
    private const val ALTERNATIVE_LAUNCHER_ACTIVITY = "dev.droidtop.shell.standard.AlternativeLauncherActivity"
    const val EXTRA_MODE = "dev.droidtop.app.EXTRA_MODE"
    const val MODE_DESKTOP = "desktop"
    const val MODE_HANDHELD = "handheld"
    const val MODE_STANDARD = "standard"

    /**
     * "Android" is only offered when droidtop actually holds a HOME role
     * (see [HomeRolePrefs]) — a user who chose "neither" during onboarding
     * has nothing for this entry to point to, so it's hidden rather than
     * shown broken.
     */
    @JvmStatic
    fun show(activity: Activity) {
        val homeImplementation = HomeRolePrefs.activeHomeImplementation(activity)
        val items = buildList {
            if (homeImplementation != HomeRolePrefs.HomeImplementation.NONE) add("Android")
            add("Desktop")
            add("Handheld")
            add("Settings")
        }
        AlertDialog.Builder(activity)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Android" -> launchHomeImplementation(activity, homeImplementation)
                    "Desktop" -> launchAppMode(activity, MODE_DESKTOP)
                    "Handheld" -> launchAppMode(activity, MODE_HANDHELD)
                    "Settings" -> launchSettings(activity)
                }
            }
            .show()
    }

    private fun launchHomeImplementation(activity: Activity, implementation: HomeRolePrefs.HomeImplementation) {
        val activityName = when (implementation) {
            HomeRolePrefs.HomeImplementation.STANDARD -> STANDARD_LAUNCHER_ACTIVITY
            HomeRolePrefs.HomeImplementation.ALTERNATIVE -> ALTERNATIVE_LAUNCHER_ACTIVITY
            HomeRolePrefs.HomeImplementation.NONE -> return
        }
        ModePrefs.setLastMode(activity, MODE_STANDARD)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(activity.packageName, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun launchAppMode(activity: Activity, mode: String) {
        ModePrefs.setLastMode(activity, mode)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(activity.packageName, APP_MAIN_ACTIVITY)
            putExtra(EXTRA_MODE, mode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun launchSettings(activity: Activity) {
        activity.startActivity(
            Intent(activity, com.android.launcher3.settings.SettingsActivity::class.java),
        )
    }
}

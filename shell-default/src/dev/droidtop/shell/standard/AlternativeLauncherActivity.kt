package dev.droidtop.shell.standard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * No-UI stub matching `farmerbb/Taskbar`'s real, shipping `HSLActivity`
 * (verified against its actual source this session): holds the
 * `CATEGORY_HOME` role droidtop needs for its own mode-switcher to stay
 * reachable, but immediately forwards to a *different* installed
 * launcher's own HOME activity instead of rendering anything itself.
 * Disabled by default in the manifest -- only enabled at runtime via
 * [HomeRolePrefs.setActiveHomeImplementation] when the user picks
 * "Alternative" during onboarding or in Settings.
 *
 * Mirrors `com.android.launcher3.Launcher`'s own real cold-boot redirect
 * (its `mDroidtopPendingModeRedirect` handling, already built and shipping)
 * -- without this, picking Alternative as the home implementation but
 * Desktop/Handheld as the *default mode* would always forward to the other
 * launcher instead, since this activity (not Launcher.java) is what
 * actually runs on boot when Alternative is active.
 */
class AlternativeLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lastMode = ModePrefs.lastMode(this)
        if (savedInstanceState == null && isTaskRoot && lastMode != BackButtonMenu.MODE_STANDARD) {
            val redirect = Intent(Intent.ACTION_MAIN).apply {
                setClassName(packageName, "dev.droidtop.app.MainActivity")
                putExtra(BackButtonMenu.EXTRA_MODE, lastMode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(redirect)
            finish()
            return
        }

        val target = HomeRolePrefs.alternativeTarget(this)
        if (target == null || !isInstalled(target)) {
            // Nothing valid configured -- send the user back to pick one
            // rather than looping forever or crashing on a bad component.
            HomeRolePrefs.setActiveHomeImplementation(this, HomeRolePrefs.HomeImplementation.NONE)
            startActivity(
                Intent(this, com.android.launcher3.settings.SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }

        val forwardIntent = intent.apply { component = target }
        startActivity(forwardIntent)
        // Fired a second time via a Handler.post -- same real fix
        // farmerbb/Taskbar's HSLActivity uses (its own comment: "to fix
        // launchers that specifically listen for home button presses,
        // i.e. to jump to the default panel"), not guessed.
        Handler(Looper.getMainLooper()).post { startActivity(Intent(forwardIntent)) }
        finish()
    }

    private fun isInstalled(component: ComponentName): Boolean = try {
        packageManager.getActivityInfo(component, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

package dev.droidtop.shell.standard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * The `CATEGORY_HOME` component when the person chose another launcher ("Alternative", during onboarding or in
 * Settings): droidtop keeps the Home role, so its mode switcher stays reachable, and forwards to that launcher's own
 * HOME activity instead of drawing anything itself, the way farmerbb/Taskbar's HSLActivity does. Disabled in the
 * manifest until [HomeRolePrefs.setActiveHomeImplementation] enables it.
 *
 * A Home press is decided the same way as Standard's ([HomeEntry.route]): setup first, the companion's screen, an
 * explicit "Android", a crash-looping theme, the default or last mode with its display reinit and double tap. Only
 * when that answers Standard does it forward to the other launcher. A recreated activity forwards without deciding
 * again.
 */
class AlternativeLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null && HomeEntry.route(this, intent) == HomeEntry.Route.HANDLED) {
            finish()
            return
        }

        val forwardTo = HomeRolePrefs.alternativeTarget(this)
        if (forwardTo == null || !isInstalled(forwardTo)) {
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

        HomeEntry.enterStandard(this)
        val forwardIntent = intent.apply {
            component = forwardTo
            removeExtra(BackButtonMenu.EXTRA_MODE)
        }
        startActivity(forwardIntent)
        // Started a second time on the next frame, as farmerbb/Taskbar's HSLActivity does: some launchers move to their
        // default page only on a Home intent that reaches them while already in front (docs/SPEC.md 2c).
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

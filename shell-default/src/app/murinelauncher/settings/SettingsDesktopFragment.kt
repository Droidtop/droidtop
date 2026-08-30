package app.murinelauncher.settings

import android.content.ComponentName
import android.content.Intent
import androidx.preference.Preference
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController

/**
 * Desktop-mode preferences, reachable from the same root settings screen as
 * every other mode (Standard, Handheld) so settings stay one consistent
 * surface rather than one screen per shell. Read directly by
 * dev.droidtop.shell.desktop.DesktopShell via the shared
 * "com.android.launcher3.prefs" SharedPreferences file (see
 * dev.droidtop.app.MainActivity, which wires the read value through).
 */
public final class SettingsDesktopFragment : AbstractSettingsFragment() {

    companion object {
        const val PREF_ROOT_COMPOSITOR_SETUP: String = "pref_desktop_root_compositor_setup"
        const val PREF_CONTAINERS: String = "pref_desktop_containers"
    }

    override fun getPreferenceScreenResId() = R.xml.droidtop_desktop_prefs

    override fun getPreferenceTitle(): Int = R.string.pref_category_desktop_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        if (preference.key == PREF_ROOT_COMPOSITOR_SETUP) {
            // Re-entry into onboarding's own DESKTOP_SETUP step -- same
            // explicit-component-name pattern SettingsHandheldFragment's
            // own PREF_GAME_FOLDERS/PREF_CONSOLE_SYSTEMS use, same reasoning
            // (:shell-default can't compile-depend on :app).
            preference.setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(requireContext().packageName, "dev.droidtop.app.OnboardingActivity")
                    putExtra("dev.droidtop.app.EXTRA_START_STEP", "DESKTOP_SETUP")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                true
            }
        }
        if (preference.key == PREF_CONTAINERS) {
            // The container/distro manager (docs/SPEC.md section 3d) --
            // action-string launch, same no-compile-dependency reasoning
            // as above and as DesktopShell's own taskbar button.
            preference.setOnPreferenceClickListener {
                startActivity(
                    Intent("dev.droidtop.app.action.CONTAINERS").apply {
                        setPackage(requireContext().packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                true
            }
        }
        return true
    }
}

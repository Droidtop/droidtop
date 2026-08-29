package app.murinelauncher.settings

import android.content.Intent
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController
import dev.droidtop.shell.standard.HomeRolePrefs

/**
 * Real droidtop-wide preferences: config that affects the whole app (which
 * HOME role it holds) or is genuinely outside every shell's own UI (the
 * real Android system Settings shortcut) -- NOT Standard's own launcher
 * preferences (General/Icons/Home/Drawer/QSB/Misc, `SettingsRootFragment`),
 * which are a real per-shell settings surface on par with Desktop's and
 * Handheld's own, not "global" in this sense. Reachable from every shell's
 * own settings screen (see droidtop_handheld_prefs.xml/droidtop_desktop_prefs.xml's
 * own "Global settings" entries) and, symmetrically, links back out to
 * all three shells' own settings itself.
 */
public final class SettingsGlobalFragment : AbstractSettingsFragment() {

    companion object {
        const val PREF_HOME_ROLE: String = "pref_global_home_role"
        const val PREF_SYSTEM_SETTINGS: String = "pref_global_system_settings"
    }

    override fun getPreferenceScreenResId() = R.xml.droidtop_global_prefs

    override fun getPreferenceTitle(): Int = R.string.pref_global_settings_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        when (preference.key) {
            PREF_HOME_ROLE -> {
                if (preference is SwitchPreferenceCompat) {
                    val context = requireContext()
                    // STANDARD <-> NONE only -- a user already on ALTERNATIVE
                    // (a real, separate onboarding flow: picking which
                    // OTHER installed launcher droidtop's own
                    // AlternativeLauncherActivity forwards to) isn't
                    // silently reassigned by this simple on/off toggle;
                    // re-running onboarding's own HOME_ROLE step is the
                    // real way to reach or leave that state.
                    preference.isChecked = HomeRolePrefs.activeHomeImplementation(context) == HomeRolePrefs.HomeImplementation.STANDARD
                    preference.setOnPreferenceChangeListener { _, newValue ->
                        val enabled = newValue as Boolean
                        HomeRolePrefs.setActiveHomeImplementation(
                            context,
                            if (enabled) HomeRolePrefs.HomeImplementation.STANDARD else HomeRolePrefs.HomeImplementation.NONE,
                        )
                        true
                    }
                }
            }
            PREF_SYSTEM_SETTINGS -> {
                // Real Android system Settings app, not droidtop's own --
                // Settings.ACTION_SETTINGS is the standard real entry
                // point every launcher's own "System settings" shortcut
                // uses.
                preference.setOnPreferenceClickListener {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    true
                }
            }
        }
        return true
    }
}

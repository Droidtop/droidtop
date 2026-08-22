package app.murinelauncher.settings

import android.content.ComponentName
import android.content.Intent
import androidx.preference.ListPreference
import androidx.preference.Preference
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController

/**
 * Handheld-mode preferences, reachable from the same root settings screen as
 * every other mode (Standard, Desktop) so settings stay one consistent
 * surface rather than one screen per shell. Read directly by
 * dev.droidtop.shell.gamepad.GamepadShell via the shared
 * "com.android.launcher3.prefs" SharedPreferences file (see
 * dev.droidtop.app.MainActivity, which wires the read value through).
 */
public final class SettingsHandheldFragment : AbstractSettingsFragment() {

    companion object {
        const val PREF_DEFAULT_SECTION: String = "pref_handheld_default_section"
        const val PREF_CONSOLE_SYSTEMS: String = "pref_handheld_console_systems"
        const val PREF_APPS_GRID_COLUMNS: String = "pref_handheld_apps_grid_columns"
    }

    override fun getPreferenceScreenResId() = R.xml.droidtop_handheld_prefs

    override fun getPreferenceTitle(): Int = R.string.pref_category_handheld_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        when (preference.key) {
            PREF_DEFAULT_SECTION -> {
                if (preference is ListPreference) {
                    preference.summary = preference.entry
                    preference.setOnPreferenceChangeListener { pref, newValue ->
                        val entry = (pref as ListPreference).entries.getOrNull(pref.findIndexOfValue(newValue as String))
                        pref.summary = entry
                        true
                    }
                }
            }
            PREF_CONSOLE_SYSTEMS -> {
                // Was previously only reachable from inside GamepadShell's
                // own in-shell Settings tab, disconnected from this real
                // Preference hierarchy every other setting lives in --
                // folded in here so it's reachable the same way regardless
                // of which mode's Settings you opened from. No compile-time
                // dependency on :app (see OnboardingGate's own doc comment
                // for why that dependency can't exist), so this launches by
                // explicit component name, same established pattern.
                preference.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(requireContext().packageName, "dev.droidtop.app.ConsoleSystemsActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }
        }
        return true
    }
}

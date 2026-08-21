package app.murinelauncher.settings

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
    }

    override fun getPreferenceScreenResId() = R.xml.murine_prefs_handheld

    override fun getPreferenceTitle(): Int = R.string.pref_category_handheld_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        if (preference is ListPreference && preference.key == PREF_DEFAULT_SECTION) {
            preference.summary = preference.entry
            preference.setOnPreferenceChangeListener { pref, newValue ->
                val entry = (pref as ListPreference).entries.getOrNull(pref.findIndexOfValue(newValue as String))
                pref.summary = entry
                true
            }
        }
        return true
    }
}

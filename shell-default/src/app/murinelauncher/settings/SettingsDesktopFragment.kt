package app.murinelauncher.settings

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

    override fun getPreferenceScreenResId() = R.xml.droidtop_desktop_prefs

    override fun getPreferenceTitle(): Int = R.string.pref_category_desktop_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean = true
}

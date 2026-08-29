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
        const val PREF_GAME_FOLDERS: String = "pref_handheld_game_folders"
        const val PREF_RESCAN_LIBRARY: String = "pref_handheld_rescan_library"
        const val PREF_APPEARANCE: String = "pref_handheld_appearance"

        // Must match dev.droidtop.app.MainActivity's own real constants --
        // no compile dependency on :app exists (see PREF_CONSOLE_SYSTEMS'
        // own doc comment for why), so these are duplicated string
        // literals, not a shared reference.
        private const val EXTRA_MODE = "dev.droidtop.app.EXTRA_MODE"
        private const val MODE_HANDHELD = "handheld"
        private const val EXTRA_HANDHELD_START_SECTION = "dev.droidtop.app.EXTRA_HANDHELD_START_SECTION"
        private const val EXTRA_HANDHELD_RESCAN = "dev.droidtop.app.EXTRA_HANDHELD_RESCAN"
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
            PREF_GAME_FOLDERS -> {
                // Re-entry into onboarding's own GAMES_FOLDERS step (per
                // direction: mode-specific setup must be re-runnable later,
                // not onboarding-only) -- same explicit-component-name
                // pattern as PREF_CONSOLE_SYSTEMS above, same reasoning.
                preference.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(requireContext().packageName, "dev.droidtop.app.OnboardingActivity")
                        putExtra("dev.droidtop.app.EXTRA_START_STEP", "GAMES_FOLDERS")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }
            PREF_RESCAN_LIBRARY -> {
                // Real, live rescan -- MainActivity.EXTRA_HANDHELD_RESCAN
                // bumps GamepadShell's own rescanTrigger state on create,
                // same real action "Rescan library" used to only be
                // reachable through inside GamepadShell's own (now-removed)
                // in-shell Settings tab.
                preference.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(requireContext().packageName, "dev.droidtop.app.MainActivity")
                        putExtra(EXTRA_MODE, MODE_HANDHELD)
                        putExtra(EXTRA_HANDHELD_RESCAN, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }
            PREF_APPEARANCE -> {
                // Theme selection/sync/browse is real, rich Compose UI
                // (ThemeBrowserScreen's own screenshot previews) that
                // belongs in GamepadShell's own composition, not
                // reimplemented as flat XML preferences here -- this just
                // jumps straight to Handheld's own Settings tab, which
                // still owns that real UI.
                preference.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(requireContext().packageName, "dev.droidtop.app.MainActivity")
                        putExtra(EXTRA_MODE, MODE_HANDHELD)
                        putExtra(EXTRA_HANDHELD_START_SECTION, "SETTINGS")
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

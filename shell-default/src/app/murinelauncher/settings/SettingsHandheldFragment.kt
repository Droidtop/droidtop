package app.murinelauncher.settings

import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeDownloader
import dev.droidtop.library.theme.ThemePrefs
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        const val PREF_THEME: String = "pref_handheld_theme"
        const val PREF_THEME_COLOR_SCHEME: String = "pref_handheld_theme_colorscheme"
        const val PREF_THEME_VARIANT: String = "pref_handheld_theme_variant"
        const val PREF_SYNC_THEME_INDEX: String = "pref_handheld_sync_theme_index"
        const val PREF_BROWSE_THEMES: String = "pref_handheld_browse_themes"
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
                // Real, live rescan -- BackButtonMenu.EXTRA_HANDHELD_RESCAN
                // bumps GamepadShell's own rescanTrigger state via
                // MainActivity's real deep-link handling, same real action
                // "Rescan library" used to only be reachable through inside
                // GamepadShell's own (now-removed) in-shell Settings tab.
                preference.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(requireContext().packageName, "dev.droidtop.app.MainActivity")
                        putExtra(BackButtonMenu.EXTRA_MODE, BackButtonMenu.MODE_HANDHELD)
                        putExtra(BackButtonMenu.EXTRA_HANDHELD_RESCAN, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }
            PREF_THEME -> {
                // Real, direct preference -- reads/writes
                // dev.droidtop.library.theme.ThemeAssets/ThemePrefs
                // (:runtime-common) directly, no bounce through a
                // differently-styled Compose screen for what's the single
                // most common theme action. entries/entryValues are set
                // here, not in the XML, since the real list of installed
                // themes is filesystem-driven (see ThemeAssets.discoverThemes's
                // own doc comment), not a compiled-in one.
                if (preference is ListPreference) {
                    val context = requireContext()
                    val themeNames = ThemeAssets.discoverThemes(context).map { it.name }.toTypedArray()
                    preference.entries = themeNames
                    preference.entryValues = themeNames
                    val active = ThemeAssets.activeThemeName(context)
                    preference.value = active
                    preference.summary = active ?: "(none found)"
                    preference.setOnPreferenceChangeListener { pref, newValue ->
                        val name = newValue as String
                        ThemePrefs.set(context, name)
                        pref.summary = name
                        true
                    }
                }
            }
            PREF_THEME_COLOR_SCHEME -> {
                // Real ES-DE parity (its own UI-Settings > Theme color
                // scheme menu): entries come from the ACTIVE theme's own
                // capabilities.xml, labels included -- DEcaffe's showcase
                // "Hyrule" look vs the first-declared "Blue dark" default
                // is exactly this setting.
                if (preference is ListPreference) {
                    val context = requireContext()
                    val caps = ThemeAssets.activeThemeCapabilities(context)
                    val themeName = ThemeAssets.activeThemeName(context)
                    val schemes = caps?.colorSchemes.orEmpty()
                    if (schemes.size <= 1 || themeName == null) {
                        preference.isVisible = false
                    } else {
                        preference.entries = schemes.map { caps?.colorSchemeLabels?.get(it) ?: it }.toTypedArray()
                        preference.entryValues = schemes.toTypedArray()
                        val current = ThemePrefs.colorScheme(context, themeName) ?: schemes.first()
                        preference.value = current
                        preference.summary = caps?.colorSchemeLabels?.get(current) ?: current
                        preference.setOnPreferenceChangeListener { pref, newValue ->
                            val scheme = newValue as String
                            ThemePrefs.setColorScheme(context, themeName, scheme)
                            pref.summary = caps?.colorSchemeLabels?.get(scheme) ?: scheme
                            true
                        }
                    }
                }
            }
            PREF_THEME_VARIANT -> {
                // Same mechanism as the color scheme above, for the
                // theme's declared variants.
                if (preference is ListPreference) {
                    val context = requireContext()
                    val caps = ThemeAssets.activeThemeCapabilities(context)
                    val themeName = ThemeAssets.activeThemeName(context)
                    val variants = caps?.variants.orEmpty()
                    if (variants.size <= 1 || themeName == null) {
                        preference.isVisible = false
                    } else {
                        preference.entries = variants.map { caps?.variantLabels?.get(it) ?: it }.toTypedArray()
                        preference.entryValues = variants.toTypedArray()
                        val current = ThemePrefs.variant(context, themeName) ?: variants.first()
                        preference.value = current
                        preference.summary = caps?.variantLabels?.get(current) ?: current
                        preference.setOnPreferenceChangeListener { pref, newValue ->
                            val variant = newValue as String
                            ThemePrefs.setVariant(context, themeName, variant)
                            pref.summary = caps?.variantLabels?.get(variant) ?: variant
                            true
                        }
                    }
                }
            }
            PREF_SYNC_THEME_INDEX -> {
                // Real, direct network action -- same real
                // ThemeDownloader.syncThemesList real ES-DE theme-index
                // clone/fetch the old Compose-only "Sync theme index" used,
                // just triggered from here now. lifecycleScope (not a
                // fire-and-forget GlobalScope) so this can't outlive the
                // Fragment view it updates the summary of.
                preference.summary = getString(R.string.pref_handheld_sync_theme_index_desc)
                preference.setOnPreferenceClickListener {
                    val context = requireContext()
                    preference.summary = "Checking..."
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ThemeDownloader.syncThemesList(ThemeAssets.userThemesDir(context))
                        }
                        preference.summary = when (result.status) {
                            ThemeDownloader.ThemeSyncStatus.CLONED -> "Theme index downloaded"
                            ThemeDownloader.ThemeSyncStatus.UPDATED -> "Theme index updated"
                            ThemeDownloader.ThemeSyncStatus.UP_TO_DATE -> "Theme index already up to date"
                            ThemeDownloader.ThemeSyncStatus.DIVERGED -> "Theme index has local changes -- skipped"
                            ThemeDownloader.ThemeSyncStatus.FAILED -> "Failed: ${result.error?.message ?: "unknown error"}"
                        }
                    }
                    true
                }
            }
            PREF_BROWSE_THEMES -> {
                // The one real, deliberate exception left: browsing/
                // downloading a NEW theme genuinely needs a rich,
                // scrollable list of remote entries with screenshot
                // previews -- ThemeBrowserScreen's own real Compose UI,
                // which only exists inside GamepadShell. Jumps directly
                // into it now (GamepadShell's own Settings section is
                // nothing but this screen once reached via deep link --
                // see GamepadShell.kt's own SettingsSection), not an
                // intermediate Appearance list with a single item left.
                preference.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(requireContext().packageName, "dev.droidtop.app.MainActivity")
                        putExtra(BackButtonMenu.EXTRA_MODE, BackButtonMenu.MODE_HANDHELD)
                        putExtra(BackButtonMenu.EXTRA_HANDHELD_START_SECTION, "SETTINGS")
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

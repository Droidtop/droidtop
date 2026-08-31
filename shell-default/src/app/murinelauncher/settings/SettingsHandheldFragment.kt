package app.murinelauncher.settings

import android.os.Bundle
import androidx.preference.Preference
import app.murinelauncher.settings.common.AbstractSettingsFragment
import app.murinelauncher.settings.common.CatalogPreferenceBuilder
import com.android.launcher3.LauncherFiles
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController
import dev.droidtop.library.settings.HandheldSettingsCatalog

/**
 * Handheld-mode preferences, reachable from the same root settings screen
 * as every other mode (Standard, Desktop) so settings stay one consistent
 * surface rather than one screen per shell. This fragment declares NO
 * settings of its own: the shared HandheldSettingsCatalog
 * (:runtime-common, docs/SPEC.md settings architecture) owns what the
 * settings are, their grouping and their write paths, and this class just
 * chromes that catalog as a real PreferenceScreen via
 * CatalogPreferenceBuilder -- the exact same catalog Handheld's own
 * in-shell settings section renders in its own themed context, so the two
 * surfaces can never drift apart. The catalog's "global" group is skipped
 * here because SettingsActivity's own persistent action-bar item already
 * IS this surface's chrome for global settings.
 */
public final class SettingsHandheldFragment : AbstractSettingsFragment() {

    // Unused: the screen is built programmatically from the catalog in
    // onCreatePreferences below, never inflated from XML.
    override fun getPreferenceScreenResId() = 0

    override fun getPreferenceTitle(): Int = R.string.pref_category_handheld_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean = true

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = LauncherFiles.SHARED_PREFERENCES_KEY
        rebuild()
        activity?.let { activity ->
            activity.title = getString(R.string.pref_category_handheld_title)
        }
    }

    private fun rebuild() {
        preferenceScreen = CatalogPreferenceBuilder.build(
            fragment = this,
            groups = HandheldSettingsCatalog.groups(requireContext()),
            skipGroupIds = setOf(HandheldSettingsCatalog.GROUP_GLOBAL),
            rebuild = ::rebuild,
        )
    }
}

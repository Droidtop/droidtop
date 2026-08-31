package app.murinelauncher.settings

import android.os.Bundle
import androidx.preference.Preference
import app.murinelauncher.settings.common.AbstractSettingsFragment
import app.murinelauncher.settings.common.CatalogPreferenceNavigator
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
 * chromes that catalog -- nested management screens (console systems,
 * platforms, ROM folders, scraper credentials, resolved through
 * SettingsScreenRegistry) included -- via CatalogPreferenceNavigator.
 * The exact same catalog Handheld's own in-shell settings section
 * renders in its themed context, so the two surfaces can never drift
 * apart. The catalog's "global" group is skipped here because
 * SettingsActivity's own persistent action-bar item already IS this
 * surface's chrome for global settings.
 */
public final class SettingsHandheldFragment : AbstractSettingsFragment() {

    private var navigator: CatalogPreferenceNavigator? = null

    // Unused: the screen is built programmatically from the catalog in
    // onCreatePreferences below, never inflated from XML.
    override fun getPreferenceScreenResId() = 0

    override fun getPreferenceTitle(): Int = R.string.pref_category_handheld_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean = true

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = LauncherFiles.SHARED_PREFERENCES_KEY
        val nav = CatalogPreferenceNavigator(
            fragment = this,
            rootGroups = { ctx -> HandheldSettingsCatalog.groups(ctx) },
            skipGroupIds = setOf(HandheldSettingsCatalog.GROUP_GLOBAL),
        )
        navigator = nav
        nav.rebuild()
        activity?.title = getString(R.string.pref_category_handheld_title)
    }
}

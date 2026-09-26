package app.murinelauncher.settings

import android.os.Bundle
import androidx.preference.Preference
import app.murinelauncher.settings.common.AbstractSettingsFragment
import app.murinelauncher.settings.common.CatalogPreferenceNavigator
import com.android.launcher3.LauncherFiles
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController
import dev.droidtop.library.settings.SettingsScreenRegistry

/**
 * Desktop mode's settings on the Standard settings surface: the shared
 * catalog :app registers as "desktop_settings" (DroidtopWideSettings),
 * chromed as preferences, the same data the Gaming shell draws in its
 * own rows.
 */
public final class SettingsDesktopFragment : AbstractSettingsFragment() {

    private var navigator: CatalogPreferenceNavigator? = null

    // Unused: the screen is built from the catalog in onCreatePreferences.
    override fun getPreferenceScreenResId() = 0

    override fun getPreferenceTitle(): Int = R.string.pref_category_desktop_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean = true

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = LauncherFiles.SHARED_PREFERENCES_KEY
        val nav = CatalogPreferenceNavigator(
            fragment = this,
            rootGroups = { ctx -> SettingsScreenRegistry.get("desktop_settings")?.groups?.invoke(ctx).orEmpty() },
            enableSearch = true,
            rootScreenId = "desktop_settings",
            rootTitle = getString(R.string.pref_category_desktop_title),
        )
        navigator = nav
        nav.rebuild()
        activity?.title = getString(R.string.pref_category_desktop_title)
    }
}

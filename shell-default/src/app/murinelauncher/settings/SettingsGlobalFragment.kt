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
 * Global settings on the Standard settings surface: the shared catalog
 * :app registers as "global_settings" (DroidtopWideSettings), chromed as
 * preferences. The Gaming shell draws the same catalog in its own rows,
 * so the two can never drift apart. Reached from the persistent "Global
 * settings" action-bar item on every SettingsActivity screen.
 */
public final class SettingsGlobalFragment : AbstractSettingsFragment() {

    private var navigator: CatalogPreferenceNavigator? = null

    // Unused: the screen is built from the catalog in onCreatePreferences.
    override fun getPreferenceScreenResId() = 0

    override fun getPreferenceTitle(): Int = R.string.pref_global_settings_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean = true

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = LauncherFiles.SHARED_PREFERENCES_KEY
        val nav = CatalogPreferenceNavigator(
            fragment = this,
            rootGroups = { ctx -> SettingsScreenRegistry.get("global_settings")?.groups?.invoke(ctx).orEmpty() },
        )
        navigator = nav
        nav.rebuild()
        activity?.title = getString(R.string.pref_global_settings_title)
    }
}

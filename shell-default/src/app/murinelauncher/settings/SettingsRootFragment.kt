package app.murinelauncher.settings

import androidx.annotation.VisibleForTesting
import androidx.preference.Preference
import app.murinelauncher.settings.common.AbstractSettingsFragment
import app.murinelauncher.settings.common.drawableRes
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController
import dev.droidtop.library.settings.Place
import dev.droidtop.library.settings.UiModePrefs

public final class SettingsRootFragment: AbstractSettingsFragment() {

    companion object {
        @VisibleForTesting
        const val DEVELOPER_OPTIONS_KEY: String = "pref_developer_options"
        private const val PLACES_KEY = "pref_places"
        private const val PLACE_KEY_PREFIX = "pref_place_"
    }

    override fun getPreferenceScreenResId() = R.xml.murine_prefs_root

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        // droidtop: the places, from the one place list (docs/SPEC.md 7j "Places in every mode").
        val visiblePlaces = Place.visible(UiModePrefs.get(preference.context))
        if (preference.key == PLACES_KEY) return visiblePlaces.isNotEmpty()
        if (preference.key.startsWith(PLACE_KEY_PREFIX)) {
            val place = Place.byScreenId(preference.key.removePrefix(PLACE_KEY_PREFIX))
            if (place == null || place !in visiblePlaces) return false
            preference.title = place.title
            // Its own glyph, the catalog's (Droidtop/tracker#705), not the settings cog every place shared.
            preference.icon = androidx.core.content.ContextCompat.getDrawable(preference.context, place.icon.drawableRes())
            preference.intent = Place.openIntent(preference.context, place)
            return true
        }
        when (preference.key) {
            DEVELOPER_OPTIONS_KEY -> {
                if (BuildConfig.IS_STUDIO_BUILD) {
                    preference.setOrder(0)
                }
                return mDeveloperOptionsEnabled
            }
            else -> return true
        }
    }
}
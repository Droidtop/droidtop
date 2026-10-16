package app.murinelauncher.settings

import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import app.murinelauncher.graphics.WorkspaceBlurUtils
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController

public final class SettingsGeneralFragment: AbstractSettingsFragment() {

    companion object {
        const val BLUR_PREVIEW: String = "pref_blur_preview"
        const val BLUR_WARNING: String = "pref_blur_warning"
    }

    override fun getPreferenceScreenResId() = R.xml.murine_prefs_general

    override fun getPreferenceTitle(): Int? = R.string.pref_category_general_title

    private fun updateBlurWarningVisibility(value: Boolean) {
        findPreference<Preference>(BLUR_WARNING)?.isVisible =
            value && !WorkspaceBlurUtils.isBlurSupported
    }

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        when (preference.key) {
            BLUR_PREVIEW -> {
                preference as SwitchPreferenceCompat
                val currentEntry = LauncherPrefs.BLUR_PREVIEW.get(requireContext())
                val isVisible = WorkspaceBlurUtils.isBlurSupportedSDK
                preference.isChecked = currentEntry
                preference.isVisible = isVisible
                updateBlurWarningVisibility(if (isVisible) currentEntry else false)
                if (isVisible) preference.setOnPreferenceChangeListener { _, newValue ->
                    updateBlurWarningVisibility(newValue as Boolean)
                    true
                }
                return true
            }
            else -> return true
        }
    }
}
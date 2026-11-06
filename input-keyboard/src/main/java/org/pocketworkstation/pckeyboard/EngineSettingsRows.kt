package org.pocketworkstation.pckeyboard

import android.app.Activity
import android.preference.Preference
import android.preference.PreferenceGroup

/**
 * The rows a language's own engine adds to the keyboard's settings (Droidtop/tracker#903 H7c; docs/SPEC.md 6a, "The
 * engine seam"): one for each [InputEngines.SettingsRow] :app registered, at the end of the group that holds the
 * languages. What a row says is read off the main thread, since it may look at the engine's files.
 */
internal object EngineSettingsRows {
    private val worker by lazy { KeyboardThreads.single("keyboard-engine-settings") }

    /** Adds every registered row to [group]; what they say is read by [refresh], from the screen's onResume. */
    @JvmStatic
    fun addTo(activity: Activity, group: PreferenceGroup) {
        for (row in InputEngines.settingsRows()) {
            if (group.findPreference(row.key) != null) continue
            val preference = Preference(activity).apply {
                key = row.key
                title = row.title(activity)
                isPersistent = false
            }
            preference.setOnPreferenceClickListener {
                row.open(activity) { refresh(activity, group) }
                true
            }
            group.addPreference(preference)
        }
    }

    /** Reads what each row says again, off the main thread, and shows it. */
    @JvmStatic
    fun refresh(activity: Activity, group: PreferenceGroup) {
        val app = activity.applicationContext
        for (row in InputEngines.settingsRows()) {
            val preference: Preference = group.findPreference(row.key) ?: continue
            worker.execute {
                val summary = row.summary(app)
                activity.runOnUiThread { preference.summary = summary }
            }
        }
    }
}

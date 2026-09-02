package app.murinelauncher.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.R
import com.android.launcher3.util.DisplayController
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.HomeRolePrefs
import dev.droidtop.shell.standard.ModePrefs
import org.json.JSONObject
import java.io.BufferedReader
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Real droidtop-wide preferences: config that affects the whole app, or is
 * genuinely outside every shell's own UI (which HOME role droidtop holds,
 * which app-hosted modes are enabled and which one is the real default,
 * re-running onboarding, backup/restore, the real Android system Settings
 * shortcut) -- NOT Standard's own launcher preferences (General/Icons/
 * Home/Drawer/QSB/Misc, `SettingsRootFragment`), which are a real
 * per-shell settings surface on par with Desktop's and Handheld's own, not
 * "global" in this sense. Reached via the real, persistent "Global
 * settings" action-bar item on every SettingsActivity screen (see that
 * class's own onCreateOptionsMenu) -- deliberately does NOT link back out
 * to the three shells' own settings itself; that direction already exists
 * (each shell's own screen links here and to its siblings), linking both
 * ways would just be a redundant loop.
 */
public final class SettingsGlobalFragment : AbstractSettingsFragment() {

    companion object {
        const val PREF_HOME_ROLE: String = "pref_global_home_role"
        const val PREF_DEFAULT_MODE: String = "pref_global_default_mode"
        const val PREF_ENABLE_DESKTOP: String = "pref_global_enable_desktop"
        const val PREF_ENABLE_HANDHELD: String = "pref_global_enable_handheld"
        const val PREF_RERUN_ONBOARDING: String = "pref_global_rerun_onboarding"
        const val PREF_BACKUP: String = "pref_global_backup"
        const val PREF_RESTORE: String = "pref_global_restore"
        const val PREF_SYSTEM_SETTINGS: String = "pref_global_system_settings"

        // Real, honest scope: backs up the one real SharedPreferences file
        // every droidtop-specific setting across every shell actually lives
        // in (HandheldPrefs/ThemePrefs/ModePrefs/HomeRolePrefs/
        // ConsoleSystemOverrides/etc all share it -- see e.g.
        // SettingsHandheldFragment's own doc comment). Deliberately NOT a
        // full device backup -- RomDatabase's own scan cache, downloaded
        // themes, and GamesRoots' own real folder grants are real,
        // separate, larger state a plain JSON file can't safely round-trip
        // (folder grants in particular need re-consent, not a silent
        // restore) -- a real, deferred gap, not silently pretended away.
        private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    }

    // Real ActivityResultLaunchers, registered as class-level properties
    // (Android's own required pattern -- must happen before the Fragment
    // reaches CREATED, not conditionally inside initPreference's own
    // per-preference dispatch below).
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(uri)
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readBackup(uri)
    }

    override fun getPreferenceScreenResId() = R.xml.droidtop_global_prefs

    override fun getPreferenceTitle(): Int = R.string.pref_global_settings_title

    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        when (preference.key) {
            PREF_HOME_ROLE -> {
                if (preference is SwitchPreferenceCompat) {
                    val context = requireContext()
                    // STANDARD <-> NONE only -- a user already on ALTERNATIVE
                    // (a real, separate onboarding flow: picking which
                    // OTHER installed launcher droidtop's own
                    // AlternativeLauncherActivity forwards to) isn't
                    // silently reassigned by this simple on/off toggle;
                    // re-running onboarding's own HOME_ROLE step is the
                    // real way to reach or leave that state.
                    preference.isChecked = HomeRolePrefs.activeHomeImplementation(context) == HomeRolePrefs.HomeImplementation.STANDARD
                    preference.setOnPreferenceChangeListener { _, newValue ->
                        val enabled = newValue as Boolean
                        HomeRolePrefs.setActiveHomeImplementation(
                            context,
                            if (enabled) HomeRolePrefs.HomeImplementation.STANDARD else HomeRolePrefs.HomeImplementation.NONE,
                        )
                        true
                    }
                }
            }
            PREF_ENABLE_DESKTOP, PREF_ENABLE_HANDHELD -> {
                if (preference is SwitchPreferenceCompat) {
                    val context = requireContext()
                    val mode = if (preference.key == PREF_ENABLE_DESKTOP) BackButtonMenu.MODE_DESKTOP else BackButtonMenu.MODE_HANDHELD
                    preference.isChecked = ModePrefs.isModeEnabled(context, mode)
                    preference.setOnPreferenceChangeListener { _, newValue ->
                        ModePrefs.setModeEnabled(context, mode, newValue as Boolean)
                        true
                    }
                }
            }
            PREF_DEFAULT_MODE -> {
                if (preference is ListPreference) {
                    val context = requireContext()
                    refreshDefaultModeChoices(preference, context)
                    preference.setOnPreferenceChangeListener { pref, newValue ->
                        val mode = (newValue as String).takeIf { it.isNotEmpty() }
                        ModePrefs.setDefaultMode(context, mode)
                        (pref as ListPreference).summary = pref.entries.getOrNull(pref.findIndexOfValue(newValue))
                        true
                    }
                }
            }
            PREF_RERUN_ONBOARDING -> {
                // Real re-entry into onboarding from the very start
                // (OnboardingStep.WELCOME, confirmed via
                // OnboardingActivity.kt's own `startStep ?: WELCOME`
                // default) -- omitting EXTRA_START_STEP is what does this,
                // same explicit-component-name pattern every other
                // cross-module launch in this file uses.
                preference.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(requireContext().packageName, "dev.droidtop.app.OnboardingActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }
            PREF_BACKUP -> {
                preference.setOnPreferenceClickListener {
                    backupLauncher.launch("droidtop-backup.json")
                    true
                }
            }
            PREF_RESTORE -> {
                preference.setOnPreferenceClickListener {
                    restoreLauncher.launch(arrayOf("application/json"))
                    true
                }
            }
            PREF_SYSTEM_SETTINGS -> {
                // Real Android system Settings app, not droidtop's own --
                // Settings.ACTION_SETTINGS is the standard real entry
                // point every launcher's own "System settings" shortcut
                // uses.
                preference.setOnPreferenceClickListener {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    true
                }
            }
        }
        return true
    }

    /** Real, dynamic: only offers enabled modes, and clears the stored default (falls back to real lastMode behavior) if it names a mode that just got disabled. */
    private fun refreshDefaultModeChoices(preference: ListPreference, context: Context) {
        val choices = buildList {
            add("" to "(none — use whichever was used last)")
            if (ModePrefs.isModeEnabled(context, BackButtonMenu.MODE_DESKTOP)) add(BackButtonMenu.MODE_DESKTOP to "Desktop")
            if (ModePrefs.isModeEnabled(context, BackButtonMenu.MODE_HANDHELD)) add(BackButtonMenu.MODE_HANDHELD to "Handheld")
        }
        preference.entryValues = choices.map { it.first }.toTypedArray()
        preference.entries = choices.map { it.second }.toTypedArray()
        val current = ModePrefs.defaultMode(context)?.takeIf { mode -> choices.any { it.first == mode } } ?: ""
        preference.value = current
        preference.summary = choices.firstOrNull { it.first == current }?.second
    }

    private fun writeBackup(uri: Uri) {
        val context = requireContext()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
        val json = JSONObject()
        for ((key, value) in prefs) {
            when (value) {
                is Boolean, is Int, is Long, is Float, is String -> json.put(key, value)
                is Set<*> -> json.put(key, value.toList())
                // Real, honest gap: any value type this project might add
                // later that isn't one of the above (unlikely, given every
                // real usage in this codebase is a plain primitive/String)
                // is silently skipped rather than crashing the whole
                // backup over one unexpected entry.
                else -> {}
            }
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
            Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show()
        } catch (t: Exception) {
            Toast.makeText(context, "Backup failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readBackup(uri: Uri) {
        val context = requireContext()
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().use(BufferedReader::readText)
            } ?: return
            val json = JSONObject(text)
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = json.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is String -> editor.putString(key, value)
                    is org.json.JSONArray -> {
                        val strings = (0 until value.length()).map { value.getString(it) }.toSet()
                        editor.putStringSet(key, strings)
                    }
                }
            }
            editor.apply()
            Toast.makeText(context, "Restored — restart droidtop to fully apply", Toast.LENGTH_LONG).show()
        } catch (t: Exception) {
            Toast.makeText(context, "Restore failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}

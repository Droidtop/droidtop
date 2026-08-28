package app.murinelauncher.settings

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import app.murinelauncher.backup.BackupHelper
import app.murinelauncher.settings.common.AbstractSettingsFragment
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess


public final class SettingsMiscFragment: AbstractSettingsFragment() {

    companion object {
        const val PREF_DEFAULT_LAUNCHER: String = "pref_default_launcher"
        const val PREF_DROIDTOP_HOME_SCREEN: String = "pref_droidtop_home_screen"
        const val BACKUP_EXPORT: String = "pref_backup_export"
        const val BACKUP_IMPORT: String = "pref_backup_import"
        private const val BACKUP_MIME = "application/octet-stream"
    }

    private fun getFailedString(ctx: Context): String {
        return ctx.getString(R.string.remote_action_failed, "")
            .trim().trimEnd(':', '\uFF1A').trim()
    }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        val app = context?.applicationContext ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        MODEL_EXECUTOR.execute {
            val status = BackupHelper.backup(app, uri)
            MAIN_EXECUTOR.execute {
                Toast.makeText(app, if (status) app.getString(R.string.backup_export_done)
                    else getFailedString(app), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val app = context?.applicationContext ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_import_title)
            .setMessage(R.string.backup_import_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MODEL_EXECUTOR.execute {
                    if (BackupHelper.stageRestore(app, uri)) {
                        Utilities.uwu(app)
                        exitProcess(0)
                    }
                    else MAIN_EXECUTOR.execute {
                        Toast.makeText(app, getFailedString(app), Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    override fun getPreferenceScreenResId() = R.xml.murine_prefs_misc

    override fun getPreferenceTitle(): Int? = R.string.pref_category_misc_title


    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        val context = requireContext()
        when (preference.key) {
            PREF_DEFAULT_LAUNCHER -> {
                val pm = context.packageManager
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolveInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                preference.summary = if (resolveInfo == null || resolveInfo.activityInfo.packageName == "android") "???" else
                    resolveInfo.loadLabel(pm).toString()
                preference.setOnPreferenceClickListener {
                    context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    true
                }
                return true
            }
            PREF_DROIDTOP_HOME_SCREEN -> {
                preference.summary = when (dev.droidtop.shell.standard.HomeRolePrefs.activeHomeImplementation(context)) {
                    dev.droidtop.shell.standard.HomeRolePrefs.HomeImplementation.STANDARD -> "droidtop's own launcher"
                    dev.droidtop.shell.standard.HomeRolePrefs.HomeImplementation.ALTERNATIVE -> "Forwarding to another launcher"
                    dev.droidtop.shell.standard.HomeRolePrefs.HomeImplementation.NONE -> "Not set up yet"
                }
                preference.setOnPreferenceClickListener {
                    // Re-entry into onboarding's own HOME_CHOICE step -- same
                    // explicit-component-name pattern as
                    // PREF_CONSOLE_SYSTEMS/PREF_GAME_FOLDERS.
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(context.packageName, "dev.droidtop.app.OnboardingActivity")
                        putExtra("dev.droidtop.app.EXTRA_START_STEP", "HOME_CHOICE")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                return true
            }
            BACKUP_EXPORT -> {
                preference.setOnPreferenceClickListener {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportBackup.launch("murine_backup_$date.rat")
                    true
                }
                return true
            }
            BACKUP_IMPORT -> {
                preference.setOnPreferenceClickListener {
                    importBackup.launch(arrayOf(BACKUP_MIME))
                    true
                }
                return true
            }
            else -> return true
        }
    }
}

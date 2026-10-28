package app.murinelauncher.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
        const val BACKUP_EXPORT: String = "pref_backup_export"
        const val BACKUP_IMPORT: String = "pref_backup_import"
        const val LAUNCHER_IMPORT: String = "pref_launcher_import"
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
                dev.droidtop.library.settings.Notices.flash(
                    if (status) app.getString(R.string.backup_export_done) else getFailedString(app),
                    tone = if (status) dev.droidtop.library.settings.NoticeTone.Info else dev.droidtop.library.settings.NoticeTone.Caution,
                    context = app,
                )
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
                        dev.droidtop.library.settings.Notices.flash(getFailedString(app), tone = dev.droidtop.library.settings.NoticeTone.Caution, context = app)
                    }
                }
            }.show()
    }

    override fun getPreferenceScreenResId() = R.xml.murine_prefs_misc

    override fun getPreferenceTitle(): Int? = R.string.pref_category_misc_title


    override fun initPreference(preference: Preference, info: DisplayController.Info): Boolean {
        val context = requireContext()
        when (preference.key) {
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
            LAUNCHER_IMPORT -> {
                // Droidtop/tracker#564: a Nova or Apex backup as Standard's home screen.
                preference.setOnPreferenceClickListener {
                    context.startActivity(Intent(context, dev.droidtop.shell.standard.LauncherImportActivity::class.java))
                    true
                }
                return true
            }
            else -> return true
        }
    }
}

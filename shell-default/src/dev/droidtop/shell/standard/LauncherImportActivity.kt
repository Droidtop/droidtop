package dev.droidtop.shell.standard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.android.launcher3.R
import com.android.launcher3.util.Executors
import dev.droidtop.library.settings.Notices
import dev.droidtop.runtime.launcherimport.BackupReader
import dev.droidtop.runtime.launcherimport.ImportCheck

/**
 * Settings > Import from Nova or Apex (docs/SPEC.md, Launcher mode "Import from Nova or Apex"; Droidtop/tracker#564):
 * the system's file picker, then a summary of what comes across, what changes on the way and what does not come
 * across with the reason for each, and only then, on Import, [LauncherImport.apply].
 */
class LauncherImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_PICK)
        } catch (e: Exception) {
            Log.w(TAG, "no file picker", e)
            fail(getString(R.string.launcher_import_failed))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        Executors.THREAD_POOL_EXECUTOR.execute {
            val read = try {
                LauncherImport.read(applicationContext, uri)
            } catch (e: BackupReader.NotABackup) {
                runOnUiThread { fail(e.message ?: getString(R.string.launcher_import_failed)) }
                return@execute
            } catch (e: Exception) {
                Log.w(TAG, "reading the backup failed", e)
                runOnUiThread { fail(getString(R.string.launcher_import_failed)) }
                return@execute
            }
            runOnUiThread { if (!isFinishing) summary(read) }
        }
    }

    private fun fail(reason: String) {
        if (isFinishing) return
        AlertDialog.Builder(this, R.style.DroidtopDialog)
            .setTitle(R.string.launcher_import_failed)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .show()
    }

    /** What the import will do, in three parts, each line a focusable row so the D-pad scrolls through it. */
    private fun summary(read: LauncherImport.Read) {
        val plan = read.plan
        val brought = ImportCheck.brought(plan)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun heading(text: String) = list.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(TITLE_COLOR)
            setPadding(0, dp(12), 0, dp(4))
            DialogAccessibility.heading(this)
        })
        fun line(text: String) = list.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(ROW_COLOR)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isFocusable = true
            background = getDrawable(R.drawable.droidtop_list_selector)
        })
        if (brought.isEmpty()) {
            line(getString(R.string.launcher_import_nothing))
        } else {
            heading(getString(R.string.launcher_import_brought))
            brought.forEach(::line)
            line(getString(R.string.launcher_import_replaces))
        }
        plan.notes.filter { it.imported }.takeIf { it.isNotEmpty() }?.let { notes ->
            heading(getString(R.string.launcher_import_changed))
            notes.forEach { line("${it.what}: ${it.reason}") }
        }
        plan.notes.filter { !it.imported }.takeIf { it.isNotEmpty() }?.let { notes ->
            heading(getString(R.string.launcher_import_left_out))
            notes.forEach { line("${it.what}: ${it.reason}") }
        }
        val builder = AlertDialog.Builder(this, R.style.DroidtopDialog)
            .setTitle(getString(R.string.launcher_import_heading, read.source.label))
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .setOnCancelListener { finish() }
        if (brought.isNotEmpty()) {
            builder.setPositiveButton(R.string.launcher_import_go) { _, _ -> import(read) }
        }
        val dialog = builder.create()
        dialog.setOnDismissListener { if (!importing) finish() }
        dialog.show()
        // The pad starts on the first line, so the summary is read before the buttons are reached.
        (0 until list.childCount).map(list::getChildAt).firstOrNull(View::isFocusable)?.requestFocus()
    }

    private var importing = false

    private fun import(read: LauncherImport.Read) {
        importing = true
        val working = AlertDialog.Builder(this, R.style.DroidtopDialog)
            .setMessage(R.string.launcher_import_working)
            .setCancelable(false)
            .show()
        val app = applicationContext
        Executors.THREAD_POOL_EXECUTOR.execute {
            val done = try {
                LauncherImport.apply(app, read.plan)
                true
            } catch (e: Exception) {
                Log.w(TAG, "applying the import failed", e)
                false
            }
            runOnUiThread {
                working.dismiss()
                if (done) {
                    Notices.flash(app.getString(R.string.launcher_import_done, read.source.label), context = app)
                    finish()
                } else {
                    fail(getString(R.string.launcher_import_failed))
                }
            }
        }
    }

    companion object {
        private const val TAG = "droidtop.LauncherImport"
        private const val REQUEST_PICK = 1
        private const val TITLE_COLOR = 0xFFEDEDED.toInt()
        private const val ROW_COLOR = 0xFFD0D0D0.toInt()
    }
}

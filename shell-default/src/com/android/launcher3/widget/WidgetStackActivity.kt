package com.android.launcher3.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.util.Executors

/**
 * droidtop (Droidtop/tracker#564): "Stack a widget here", from a widget's resize frame. Lists the widgets
 * this device has, then gives the chosen one an id on the launcher's own widget host, binds it (asking the
 * person when the system wants that) and runs its setup screen if it has one, and finally puts it in the
 * stack of the widget the frame was on ([WidgetStacks.stack]), where it shows. Leaving at any step frees the
 * id again.
 */
class WidgetStackActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private var shown = 0
    private var allocated = 0
    private var chosen: AppWidgetProviderInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shown = intent.getIntExtra(EXTRA_SHOWN, 0)
        host = AppWidgetHost(this, LauncherWidgetHolder.APPWIDGET_HOST_ID)
        if (savedInstanceState != null) {
            // Back from a bind or setup screen after the process was recreated: start over rather than guess.
            savedInstanceState.getInt(STATE_ALLOCATED).takeIf { it != 0 }?.let { host.deleteAppWidgetId(it) }
            finish()
            return
        }
        // Labels load the providers' resources: off the main thread.
        val app = applicationContext
        Executors.THREAD_POOL_EXECUTOR.execute {
            val providers = AppWidgetManager.getInstance(app).getInstalledProvidersForProfile(Process.myUserHandle())
            val labelled = providers.map { it to label(app, it) }.sortedBy { it.second.lowercase() }
            runOnUiThread { choose(labelled) }
        }
    }

    // Gone without a choice and not just rotating (Don't keep activities, a task cleared): the allocated id is freed
    // here, so no widget id is left behind until the next ghost-widget cleanup (Droidtop/tracker#771). A rotation
    // keeps it for the recreated activity, which frees it in onCreate.
    override fun onDestroy() {
        if (allocated != 0 && !isChangingConfigurations) {
            host.deleteAppWidgetId(allocated)
            allocated = 0
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ALLOCATED, allocated)
    }

    private fun choose(providers: List<Pair<AppWidgetProviderInfo, String>>) {
        if (isFinishing || isDestroyed) return
        if (providers.isEmpty()) {
            AlertDialog.Builder(this, R.style.DroidtopDialog)
                .setTitle(R.string.widget_stack_here)
                .setMessage(R.string.widget_stack_none)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { finish() }
                .show()
            return
        }
        AlertDialog.Builder(this, R.style.DroidtopDialog)
            .setTitle(R.string.widget_stack_here)
            .setItems(providers.map { it.second }.toTypedArray()) { _, which -> bind(providers[which].first) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun bind(info: AppWidgetProviderInfo) {
        chosen = info
        allocated = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(this)
        if (manager.bindAppWidgetIdIfAllowed(allocated, info.profile, info.provider, null)) {
            configure()
            return
        }
        val ask = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocated)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        runCatching { startActivityForResult(ask, REQUEST_BIND) }.onFailure { giveUp() }
    }

    private fun configure() {
        val info = chosen ?: return giveUp()
        if (info.configure == null) return done()
        runCatching {
            host.startAppWidgetConfigureActivityForResult(this, allocated, 0, REQUEST_CONFIGURE, null)
        }.onFailure { giveUp() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            resultCode != RESULT_OK -> giveUp()
            requestCode == REQUEST_BIND -> configure()
            requestCode == REQUEST_CONFIGURE -> done()
        }
    }

    private fun done() {
        val launcher = Launcher.ACTIVITY_TRACKER.getCreatedContext<Launcher>()
        val info = chosen
        if (launcher == null || info == null) return giveUp()
        WidgetStacks.stack(launcher, shown, allocated, info.provider)
        allocated = 0
        finish()
    }

    private fun giveUp() {
        if (allocated != 0) host.deleteAppWidgetId(allocated)
        allocated = 0
        finish()
    }

    companion object {
        private const val EXTRA_SHOWN = "shown_app_widget_id"
        private const val STATE_ALLOCATED = "allocated"
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2

        /** Opens the picker to stack a widget on the widget [shown] (its app widget id). */
        @JvmStatic
        fun start(context: Context, shown: Int) {
            context.startActivity(Intent(context, WidgetStackActivity::class.java).putExtra(EXTRA_SHOWN, shown))
        }

        private fun label(context: Context, info: AppWidgetProviderInfo): String {
            val app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(info.provider.packageName, 0)) }
                .getOrNull()?.toString().orEmpty()
            val widget = runCatching { info.loadLabel(context.packageManager) }.getOrNull().orEmpty()
            return if (app.isEmpty() || app == widget) widget.ifEmpty { info.provider.className } else "$app: $widget"
        }
    }
}

package dev.droidtop.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * Adds one widget to the companion's Home (docs/SPEC.md "The companion's tabs"): Android's own picker
 * (`ACTION_APPWIDGET_PICK`, which handles the bind permission), then the widget's own configure screen when it has
 * one, and the id kept in [CompanionWidgetPrefs]. A translucent Activity of its own because binding needs an Activity
 * result, which the second-screen hosts (a Presentation, the registry's surface) cannot receive: every companion host
 * opens this one, on its own screen, so Standard's second screen can add widgets like Gaming's (Droidtop/tracker#347).
 */
class CompanionWidgetPickActivity : Activity() {
    private var widgetId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = savedInstanceState?.getInt(KEY_ID, -1) ?: -1
        if (savedInstanceState != null) return
        widgetId = CompanionWidgets.host(this).allocateAppWidgetId()
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            REQUEST_PICK,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ID, widgetId)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) ?: widgetId
        if (resultCode != RESULT_OK || id == -1) {
            if (id != -1) CompanionWidgets.host(this).deleteAppWidgetId(id)
            finish()
            return
        }
        if (requestCode == REQUEST_PICK) {
            // A picked widget may need its own configuration screen before it is usable: the standard host flow.
            val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
            if (info?.configure != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    REQUEST_CONFIGURE,
                )
                return
            }
        }
        CompanionWidgetPrefs.add(this, id)
        finish()
    }

    private companion object {
        const val REQUEST_PICK = 71
        const val REQUEST_CONFIGURE = 72
        const val KEY_ID = "widget_id"
    }
}

/**
 * Add widget and Remove widget, the same on every companion host: Add opens [CompanionWidgetPickActivity] on this
 * screen, Remove takes the last widget off.
 */
@Composable
internal fun CompanionWidgetControls() {
    val context = LocalContext.current
    val view = LocalView.current
    val ids by CompanionWidgetPrefs.ids.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        CompanionPill("Add widget") {
            val options = android.app.ActivityOptions.makeBasic()
            view.display?.displayId?.let { options.setLaunchDisplayId(it) }
            runCatching {
                context.startActivity(
                    Intent(context, CompanionWidgetPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    options.toBundle(),
                )
            }
        }
        if (ids.isNotEmpty()) CompanionPill("Remove widget") { CompanionWidgetPrefs.removeLast(context) }
    }
}

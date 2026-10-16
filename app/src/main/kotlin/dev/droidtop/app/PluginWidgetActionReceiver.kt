package dev.droidtop.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.droidtop.library.integrations.PluginWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A press on a plugin's part of a droidtop widget (docs/plugin-api.md 3 C10 and C2, Droidtop/tracker#563). The widget
 * renderer's taps are [intent]s to this receiver; it calls the plugin through the broker off the main thread
 * ([PluginWidgets.act]), shows the reply's message, and asks the renderer to redraw that widget
 * ([PluginWidgets.onActed]). Not exported, and reached only by droidtop's own immutable PendingIntents.
 */
class PluginWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_SOURCE) ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION)
        val widgetId = intent.getIntExtra(EXTRA_WIDGET, -1)
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = runCatching { PluginWidgets.act(app, key, actionId) }.getOrNull()
                outcome?.message?.let { message ->
                    val name = PluginWidgets.find(app, key)?.pluginLabel
                    val text = if (name == null) message else "$name: $message"
                    Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
                }
                if (widgetId != -1) runCatching { PluginWidgets.onActed?.invoke(app, widgetId) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "dev.droidtop.app.PLUGIN_WIDGET_ACTION"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_WIDGET = "appWidgetId"

        // A press must outlive the receiver's own onReceive; goAsync keeps the process for it.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * The tap for [actionId] (null for a tile's press) on [sourceKey] ([PluginWidgets.Source.key]) in widget
         * [appWidgetId]. Each tap has its own data URI, so the PendingIntents of different taps never replace each other.
         */
        fun intent(context: Context, appWidgetId: Int, sourceKey: String, actionId: String?): PendingIntent {
            val intent = Intent(context, PluginWidgetActionReceiver::class.java)
                .setAction(ACTION)
                .setData(Uri.Builder().scheme("droidtop-widget").authority(appWidgetId.toString()).appendPath(sourceKey).appendPath(actionId ?: "").build())
                .putExtra(EXTRA_SOURCE, sourceKey)
                .putExtra(EXTRA_WIDGET, appWidgetId)
            actionId?.let { intent.putExtra(EXTRA_ACTION, it) }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

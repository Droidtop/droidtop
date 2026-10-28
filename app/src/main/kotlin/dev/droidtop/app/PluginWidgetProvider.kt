package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import dev.droidtop.library.integrations.PluginWidgets
import java.util.concurrent.ConcurrentHashMap

/**
 * The Plugin widget (docs/plugin-api.md 3 C10 and C2, Droidtop/tracker#563): one plugin's `ui.widget`, quick tile or
 * status tile on any launcher's page, drawn by droidtop's one renderer ([DroidtopWidgets.rows]) from the data
 * [PluginWidgets] asks of the plugin. The source is chosen in [PluginWidgetConfigureActivity] and kept in
 * [WidgetConfigs]. A tap is a [PluginWidgetActionReceiver] broadcast; after it the widget is drawn again with a fresh
 * snapshot ([PluginWidgets.onActed], installed by :app). The widget's size is the launcher's (its options), so a
 * plugin answers for the space it has.
 */
class PluginWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, PluginWidgetProvider::class.java, ids, ::render)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Resized: the plugin answers again for the new size.
        requestUpdate(context, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        WidgetConfigs.forget(context, ids)
    }

    companion object {
        /** Widgets to draw with a fresh snapshot, not the last one: right after a press on them. */
        private val forced = ConcurrentHashMap.newKeySet<Int>()

        /** Draws widget [id] again; [force] asks the plugin rather than reusing the last answer (after a press). */
        fun requestUpdate(context: Context, id: Int, force: Boolean = false) {
            if (force) forced += id
            DroidtopWidgets.update(context, PluginWidgetProvider::class.java, intArrayOf(id), ::render)
        }

        private suspend fun render(context: Context, id: Int): RemoteViews {
            val look = DroidtopWidgets.look(context)
            val title = context.getString(R.string.widget_plugin_title)
            val choose = DroidtopWidgets.activity(
                context,
                id,
                "plugin-widget-choose-$id",
                Intent(context, PluginWidgetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            )
            val key = WidgetConfigs.pluginSource(context, id)
                ?: return chooseFirst(context, look, title, context.getString(R.string.widget_plugin_unset), choose)
            val source = PluginWidgets.find(context, key)
                ?: return chooseFirst(context, look, title, context.getString(R.string.widget_plugin_gone), choose)
            val force = forced.remove(id)
            var image: android.graphics.Bitmap? = null
            val content = when (source.kind) {
                PluginWidgets.Kind.WIDGET -> {
                    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
                    val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 110
                    val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 80
                    val snapshot = PluginWidgets.snapshot(context, source, width, height, force)
                        ?: return DroidtopWidgets.rows(
                            context, look, source.label, emptyList(),
                            context.getString(R.string.widget_plugin_waiting, source.pluginLabel), R.layout.widget_plugin,
                        )
                    image = snapshot.image
                    PluginWidgetContent.of(snapshot.template, source.label)
                }
                PluginWidgets.Kind.QUICK -> PluginWidgetContent.tile(source.label, PluginWidgets.tileState(context, source, force), quick = true)
                PluginWidgets.Kind.STATUS -> PluginWidgetContent.tile(source.label, PluginWidgets.tileState(context, source, force), quick = false)
            }
            val rows = content.lines.map { line ->
                val tap = when {
                    line.press -> PluginWidgetActionReceiver.intent(context, id, key, null)
                    line.action != null -> PluginWidgetActionReceiver.intent(context, id, key, line.action)
                    else -> null
                }
                DroidtopWidgets.Row(line.text, tap)
            }
            val views = DroidtopWidgets.rows(context, look, content.title, rows, content.empty, R.layout.widget_plugin)
            image?.let {
                views.setImageViewBitmap(R.id.widget_image, it)
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
            }
            val whole = content.action?.let { PluginWidgetActionReceiver.intent(context, id, key, it) }
                // Nothing of the plugin's own to press (a status tile, a snapshot with no action): the tap opens
                // droidtop's Plugins place, where the plugin is, instead of falling to the launcher, which reopened
                // the source chooser (RP5, build 2011, Droidtop/tracker#563).
                ?: if (rows.none { it.tap != null }) {
                    DroidtopWidgets.activity(
                        context,
                        id,
                        "plugin-widget-place-$id",
                        dev.droidtop.library.settings.CatalogScreenLink.intent(context, dev.droidtop.app.settings.AppSettingsCatalogs.SCREEN_PLUGINS),
                    )
                } else {
                    null
                }
            whole?.let { views.setOnClickPendingIntent(R.id.widget_root, it) }
            return views
        }

        /** No source, or one that is gone: say so, and a tap opens the chooser. */
        private fun chooseFirst(context: Context, look: DroidtopWidgets.Look, title: String, text: String, choose: android.app.PendingIntent): RemoteViews {
            val views = DroidtopWidgets.rows(context, look, title, emptyList(), text, R.layout.widget_plugin)
            views.setOnClickPendingIntent(R.id.widget_root, choose)
            return views
        }
    }
}

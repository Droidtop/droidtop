package dev.droidtop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.theme.RoleTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one renderer every droidtop home-screen widget goes through (docs/SPEC.md, "Widgets on any launcher";
 * Droidtop/tracker#563): any launcher's pages (Niagara, Lawnchair, a stock one, Standard's own) host them through
 * Android's AppWidget surface. A widget's views are built off the main thread in [scope], from what droidtop already
 * holds in memory, and drawn in the role tokens ([RoleTokens], the palette Light or dark and the Gaming theme give,
 * #559), so a widget looks like droidtop's own screens and follows Appearance. Taps go through droidtop's own entry
 * points (a game through [GameLaunchActivity], so sessions, Kill and the watchdog hold), never a second launch path.
 */
internal object DroidtopWidgets {
    /** Widget work outlives the broadcast that asked for it, but not the process. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every droidtop widget provider, for [refreshAll]. */
    internal val PROVIDERS = listOf(
        ContinuePlayingWidgetProvider::class.java,
        PluginStatusWidgetProvider::class.java,
        LibrarySearchWidgetProvider::class.java,
        QuickLaunchWidgetProvider::class.java,
        DownloadsWidgetProvider::class.java,
        DesktopWidgetProvider::class.java,
        ShelfWidgetProvider::class.java,
        CompanionWidgetProvider::class.java,
        FullPageWidgetProvider::class.java,
        PluginWidgetProvider::class.java,
    )

    /** The four rows [rows] fills, in order. */
    private val ROW_IDS = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3, R.id.widget_row4)
    const val MAX_ROWS = 4

    /** The colours a widget is drawn in: the role tokens in use now. Reads a preference, so off the main thread. */
    class Look(val light: Boolean, val surface: Int, val ink: Int, val muted: Int)

    fun look(context: Context): Look = RoleTokens.current(context).let {
        Look(it.light, it.color("surface"), it.color("onSurface"), it.color("onSurfaceMuted"))
    }

    /** One row of a [rows] widget: its text and what a tap does (null: nothing). */
    class Row(val text: String, val tap: PendingIntent?)

    /**
     * A title and up to [MAX_ROWS] rows, or [empty] when there are none: an honest empty state, never placeholder
     * data. [layout] is `widget_rows`, or a larger layout that holds the same ids (the full page, `widget_page`).
     */
    fun rows(
        context: Context,
        look: Look,
        title: String,
        rows: List<Row>,
        empty: String,
        layout: Int = R.layout.widget_rows,
        textSp: Float? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        plate(views, look)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextColor(R.id.widget_title, look.ink)
        views.setTextViewText(R.id.widget_empty, empty)
        views.setTextColor(R.id.widget_empty, look.muted)
        views.setViewVisibility(R.id.widget_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
        for ((index, id) in ROW_IDS.withIndex()) {
            val row = rows.getOrNull(index)
            views.setViewVisibility(id, if (row == null) View.GONE else View.VISIBLE)
            if (row == null) continue
            views.setTextViewText(id, row.text)
            views.setTextColor(id, look.ink)
            textSp?.let { views.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, it) }
            row.tap?.let { views.setOnClickPendingIntent(id, it) }
        }
        return views
    }

    /**
     * The plate behind a widget (`R.id.widget_root`): the role tokens' surface colour where RemoteViews can tint a
     * background (Android 12 and later), else the light or dark plate for the appearance.
     */
    fun plate(views: RemoteViews, look: Look) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_surface)
            views.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(look.surface))
        } else {
            views.setInt(R.id.widget_root, "setBackgroundResource", if (look.light) R.drawable.widget_surface_light else R.drawable.widget_surface_dark)
        }
    }

    /**
     * A tap on widget [widgetId] that opens [intent], distinct per widget and per [key] ([WidgetTaps]): no two rows,
     * and no two widgets, share one PendingIntent, so one widget's update never rewrites another's tap.
     */
    fun activity(context: Context, widgetId: Int, key: String, intent: Intent): PendingIntent {
        // An intent naming its component is matched by it alone, so the address only tells taps apart.
        if (intent.component != null && intent.data == null) intent.data = android.net.Uri.parse(dev.droidtop.runtime.WidgetTaps.address(widgetId, key))
        return PendingIntent.getActivity(
            context,
            dev.droidtop.runtime.WidgetTaps.requestCode(widgetId, key),
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The library's games, from the same in-RAM index every surface reads, never a folder walk. In a cold process (a
     * widget is the first thing to touch the library) a scan is started and waited for briefly, rather than drawing an
     * empty widget until the next periodic update.
     */
    suspend fun libraryGames(context: Context): List<LibraryEntry> {
        val library = LibraryCore.library(context)
        val state = library.backgroundScanState(LibraryKinds.GAMES)
        return state.value ?: run {
            library.scanInBackground(LibraryKinds.GAMES)
            withTimeoutOrNull(SCAN_WAIT_MS) { state.filterNotNull().first() }
        } ?: emptyList()
    }

    private const val SCAN_WAIT_MS = 5_000L

    /** Builds and pushes each placed widget of [provider], each in [scope]. */
    fun update(context: Context, provider: Class<*>, ids: IntArray? = null, build: suspend (Context, Int) -> RemoteViews) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val targets = ids ?: manager.getAppWidgetIds(ComponentName(app, provider))
        for (id in targets) {
            scope.launch {
                val views = runCatching { build(app, id) }
                    .onFailure { android.util.Log.w("droidtop.Widgets", "${provider.simpleName} $id failed to render", it) }
                    .getOrNull() ?: return@launch
                manager.updateAppWidget(id, views)
            }
        }
    }

    /**
     * Draws every placed droidtop widget again: after Light or dark, the Gaming theme or Android's night mode changed
     * the role tokens (the hook is `RoleTokens.onChanged`, installed by :app).
     */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        for (provider in PROVIDERS) {
            val ids = manager.getAppWidgetIds(ComponentName(app, provider))
            if (ids.isEmpty()) continue
            app.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(ComponentName(app, provider))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}

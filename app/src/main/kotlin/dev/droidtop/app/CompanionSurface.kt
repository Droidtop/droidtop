package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.library.LibraryEntry

/**
 * The companion screen, in one place.
 *
 * There were two hosts for this surface and they had drifted:
 * [CompanionActivity] (used when the Handheld shell sits on the ADDON
 * screen, so the companion lands on the built-in one) drew the system
 * bar, live notifications and the user's widgets; the second-screen host
 * (the far more common arrangement — shell built-in, companion on the
 * addon) drew only [CompanionContent]'s backdrop. Since that backdrop
 * renders nothing but a wordmark until a game is focused, and nothing
 * writes [CompanionState.focusedEntry] outside a themed gamelist, the
 * addon screen showed a black rectangle with "droidtop" on it for the
 * whole time a user was browsing systems — which is exactly what it was
 * reported doing.
 *
 * One composable now, hosted by both, per the standing rule against two
 * mechanisms for one job. Whichever display the companion lands on shows
 * the same thing.
 */
@Composable
fun CompanionSurface(
    entry: LibraryEntry?,
    widgetIds: List<Int>,
    widgetManager: AppWidgetManager,
    widgetHost: AppWidgetHost,
    modifier: Modifier = Modifier,
    /**
     * Widget add/remove controls, shown only by a host that can actually
     * run them: binding a widget needs an Activity result, which a
     * `Presentation` has no way to receive. The addon screen therefore
     * displays widgets the user already added and sends them to the
     * built-in companion (or Settings) to change the set.
     */
    controls: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // droidtop's own focused-game info stays the BACKGROUND layer;
        // everything else composites above it (per direction).
        CompanionContent(entry)
        val density = LocalDensity.current
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Status + controls bar, always the first row -- the companion
            // is the glanceable screen, and "is my Wi-Fi ok / how much
            // battery" is the glance.
            CompanionSystemBar()
            // The Quick Menu's device-management surface, mirrored to the
            // always-on screen: live notifications with tap-to-open and
            // per-item dismiss, no controller needed.
            CompanionNotifications()
            widgetIds.forEach { widgetId ->
                val info = widgetManager.getAppWidgetInfo(widgetId)
                if (info != null) {
                    // minHeight is real PIXELS (AppWidgetProviderInfo),
                    // converted properly rather than reinterpreted as dp.
                    val widgetHeight = with(density) { maxOf(info.minHeight, 200).toDp() }
                    AndroidView(
                        factory = { context ->
                            widgetHost.createView(context.applicationContext, widgetId, info).apply {
                                setAppWidget(widgetId, info)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(widgetHeight)
                            .padding(vertical = 4.dp),
                    )
                }
            }
            controls?.invoke()
        }
        if (widgetIds.isEmpty()) {
            Text(
                if (controls != null) {
                    "Add widgets — music controls, calendars, anything installed."
                } else {
                    // Honest about where the action lives, rather than
                    // offering a button this host cannot run.
                    "Add widgets from the companion screen in Settings."
                },
                color = Color.DarkGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }
}

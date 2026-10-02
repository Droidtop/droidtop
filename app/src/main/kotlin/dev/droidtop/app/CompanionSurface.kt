package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.library.LibraryEntry

/**
 * The companion Home, in one place: ONE scrolling column over the ground ([CompanionContent]'s idle art),
 * so nothing is laid over anything else and whatever does not fit is a swipe away (tracker#285). Before
 * this, a measured top block (status bar, notifications, Continue playing) was stacked over a second
 * column of widgets with an "Add widgets" line pinned to the bottom of the box: a busy notification
 * block pushed the rail off screen, the pinned line was drawn across the rail, and nothing could scroll.
 *
 * Order: the status line, Continue playing, Recently added, the notification group (compact, see
 * [CompanionNotifications]), the game focused on the other screen, the user's widgets, then the host's own
 * add/remove controls as ordinary in-flow tiles. Both companion hosts draw this one composable (the
 * second-screen host and [CompanionActivity]).
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
     * `Presentation` has no way to receive. A host without them simply
     * shows the widgets the user already added; there is no line about it.
     */
    controls: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // droidtop's own idle art stays the BACKGROUND layer; the page composites above it.
        CompanionContent(entry)
        val density = LocalDensity.current
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                // A scrim, not raw text over the backdrop art: live Android notifications read as
                // unstyled system clutter laid over it (rig, p1-dt-companion-text-overlap).
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                .padding(16.dp),
        ) {
            // Status bar, always the first row (the controls live on the System tab).
            CompanionSystemBar(showControls = false)
            // Continue-playing rail: tap a recent game to launch it,
            // through the one real launch path -- see CompanionRecents.
            CompanionRecents()
            CompanionRecentlyAdded()
            // The Quick Menu's device-management surface, mirrored to the always-on screen: one compact group,
            // tap-to-open with per-item dismiss, no controller needed.
            CompanionNotifications()
            if (entry != null) CompanionFocusedInfo(entry)
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
                            .heightIn(min = widgetHeight)
                            .padding(vertical = 4.dp),
                    )
                }
            }
            controls?.invoke()
        }
    }
}

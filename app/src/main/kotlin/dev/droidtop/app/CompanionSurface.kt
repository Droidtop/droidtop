package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.library.LibraryEntry

/**
 * The companion screen, in one place.
 *
 * There were two hosts for this surface and they had drifted:
 * [CompanionActivity] (used when the Gaming shell sits on the ADDON
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
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val density = LocalDensity.current
        // The status bar / notifications / "Continue playing" rail sit
        // above the focused-game info as their own measured block, so
        // CompanionContent can reserve exactly that much top space and
        // never draw its title/description text underneath them (rig,
        // p1-dt-companion-text-overlap: they overlapped directly on the
        // console because neither composable knew the other's size).
        var topBlockHeightPx by remember { mutableStateOf(0) }
        // droidtop's own focused-game info stays the BACKGROUND layer;
        // everything else composites above it (per direction).
        CompanionContent(entry, topInset = with(density) { topBlockHeightPx.toDp() })
        Column(
            modifier = Modifier.fillMaxWidth()
                .onSizeChanged { topBlockHeightPx = it.height }
                // A real scrim, not raw text over the backdrop art: the
                // owner's own notification finding (rig,
                // p1-dt-companion-text-overlap) read live Android
                // notifications here as unstyled system clutter laid over
                // the art. They already draw through droidtop's own row
                // (CompanionNotifications, themed text + Dismiss button,
                // not the system's own notification view) -- what was
                // missing was a surface of their own to sit on, the same
                // one the clock/status row and the rail already share.
                .background(
                    MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                    RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                )
                .padding(if (portrait) 16.dp else 24.dp),
        ) {
            // Status + controls bar, always the first row -- the companion
            // is the glanceable screen, and "is my Wi-Fi ok / how much
            // battery" is the glance.
            CompanionSystemBar()
            // The Quick Menu's device-management surface, mirrored to the
            // always-on screen: live notifications with tap-to-open and
            // per-item dismiss, no controller needed.
            CompanionNotifications()
            // Continue-playing rail: tap a recent game to launch it,
            // through the one real launch path -- see CompanionRecents.
            CompanionRecents()
        }
        // Starts exactly where the measured block above ends (that
        // block already carries its own 24dp top padding) -- not a
        // second 24dp gap stacked under it.
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .padding(top = with(density) { topBlockHeightPx.toDp() }),
        ) {
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
        if (widgetIds.isEmpty()) {
            Text(
                if (controls != null) {
                    "Add widgets — music controls, calendars, anything installed."
                } else {
                    // Honest about where the action lives, rather than
                    // offering a button this host cannot run.
                    "Add widgets from the companion screen in Settings."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }
}

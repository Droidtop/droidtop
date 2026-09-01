package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.LibraryEntry

/**
 * [CompanionSurface] with its widget plumbing supplied, for callers that
 * only have a `Context` — notably the secondary-display registration,
 * which hands `:display` a composable and nothing else.
 *
 * No add/remove controls: this renders on whichever screen is NOT the one
 * the user is driving, and binding a widget needs an Activity result.
 * Widgets already added still render, and [CompanionActivity] remains
 * where the set is changed.
 */
@Composable
fun CompanionSurfaceHost(entry: LibraryEntry?) {
    val context = LocalContext.current
    val widgetManager = remember { AppWidgetManager.getInstance(context) }
    val widgetHost = remember { AppWidgetHost(context.applicationContext, WIDGET_HOST_ID) }
    val widgetIds = remember { CompanionWidgetPrefs.widgetIds(context) }

    // Without listening, a hosted widget renders once and then never
    // updates -- no clock tick, no now-playing change.
    DisposableEffect(widgetHost) {
        runCatching { widgetHost.startListening() }
        onDispose { runCatching { widgetHost.stopListening() } }
    }

    CompanionSurface(
        entry = entry,
        widgetIds = widgetIds,
        widgetManager = widgetManager,
        widgetHost = widgetHost,
        controls = null,
    )
}

// Distinct from CompanionActivity's own host id: two AppWidgetHosts
// sharing an id in one process fight over the same listener set.
private const val WIDGET_HOST_ID = 0x64726F71

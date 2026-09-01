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
    val widgetHost = remember { CompanionWidgets.host(context) }
    val widgetIds = remember { CompanionWidgetPrefs.widgetIds(context) }

    // Without listening, a hosted widget renders once and then never
    // updates -- no clock tick, no now-playing change. Reference counted,
    // since the companion can be on screen twice at once.
    DisposableEffect(Unit) {
        CompanionWidgets.startListening(context)
        onDispose { CompanionWidgets.stopListening() }
    }

    CompanionSurface(
        entry = entry,
        widgetIds = widgetIds,
        widgetManager = widgetManager,
        widgetHost = widgetHost,
        controls = null,
    )
}

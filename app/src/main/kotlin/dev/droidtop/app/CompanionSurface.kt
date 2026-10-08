package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import dev.droidtop.display.secondScreenScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.settings.CompanionHomeLayout
import dev.droidtop.library.settings.CompanionHomePrefs
import dev.droidtop.library.settings.CompanionHomeSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The companion Home (docs/SPEC.md "The companion's tabs", Droidtop/tracker#285, #328): the second screen's
 * dashboard for whatever the main screen is doing, as ONE vertically scrolling page over the ground
 * ([CompanionContent]'s idle art). Nothing is laid over anything else; the rails scroll sideways inside it.
 *
 * Order, by relevance: the status line (and the last launch or quit error), Now (the running game, else the
 * game focused in the shell), Continue playing, Recently added, Downloads and updates, Social, the notification
 * group, System, then the user's widgets with the host's own add/remove controls. Each section after the status
 * line folds from its heading and can be turned off in Displays > Companion ([CompanionHomePrefs]); a section
 * with nothing to show draws nothing. Both companion hosts draw this one composable (the second-screen host
 * and [CompanionActivity]).
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
    val context = LocalContext.current
    // The section choices are read once off the main thread; until then the defaults (everything shown) draw.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CompanionHomePrefs.load(context.applicationContext) } }
    val layout by CompanionHomePrefs.layout.collectAsState()
    Box(modifier = modifier.fillMaxSize()) {
        // droidtop's own idle art stays the BACKGROUND layer; the page composites above it.
        CompanionContent(entry)
        Column(
            modifier = Modifier.fillMaxSize()
                .secondScreenScroll(rememberScrollState())
                // A scrim, not raw text over the backdrop art: live Android notifications read as
                // unstyled system clutter laid over it (rig, p1-dt-companion-text-overlap).
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Status line, always the first row (the controls live in the System section and tab).
            CompanionSystemBar(showControls = false)
            LaunchErrorLine()
            if (layout.shows(CompanionHomeSection.NOW)) CompanionNowSection(entry, layout)
            if (layout.shows(CompanionHomeSection.CONTINUE)) CompanionRecents(layout)
            if (layout.shows(CompanionHomeSection.RECENTLY_ADDED)) CompanionRecentlyAdded(layout)
            if (layout.shows(CompanionHomeSection.APPS)) CompanionAppsSection(layout)
            if (layout.shows(CompanionHomeSection.ACTIVITY)) CompanionActivitySection(layout)
            if (layout.shows(CompanionHomeSection.SOCIAL)) CompanionSocialSection(layout)
            if (layout.shows(CompanionHomeSection.NOTIFICATIONS)) {
                val open = layout.isOpen(CompanionHomeSection.NOTIFICATIONS)
                CompanionNotifications(open = open) {
                    CompanionHomePrefs.setOpen(context, CompanionHomeSection.NOTIFICATIONS, !open)
                }
            }
            if (layout.shows(CompanionHomeSection.SYSTEM)) CompanionSystemSection(layout)
            if (layout.shows(CompanionHomeSection.WIDGETS)) {
                CompanionWidgetsSection(layout, widgetIds, widgetManager, widgetHost, controls)
            }
        }
    }
}

/**
 * Why the last launch or quit made from the companion failed. The shell's own error line is on the other
 * screen, and a log line alone left a tap here looking like it did nothing. Tap to dismiss; the next launch
 * clears it too.
 */
@Composable
private fun LaunchErrorLine() {
    val launchError by CompanionState.launchError.collectAsState()
    val message = launchError ?: return
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { CompanionState.launchError.value = null }
            .padding(top = 8.dp),
    )
}

/** The user's Android widgets, then the host's add/remove controls as ordinary rows. */
@Composable
private fun CompanionWidgetsSection(
    layout: CompanionHomeLayout,
    widgetIds: List<Int>,
    widgetManager: AppWidgetManager,
    widgetHost: AppWidgetHost,
    controls: (@Composable () -> Unit)?,
) {
    if (widgetIds.isEmpty() && controls == null) return
    val density = LocalDensity.current
    CompanionHomeSectionFrame(CompanionHomeSection.WIDGETS, layout, summary = widgetIds.size.takeIf { it > 0 }?.toString()) {
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

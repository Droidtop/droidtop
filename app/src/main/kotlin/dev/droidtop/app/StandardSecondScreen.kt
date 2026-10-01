package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.android.launcher3.allapps.RecentAppsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standard's own second screen (docs/SPEC.md 4c) -- a launcher-style
 * surface, not the game companion CompanionSurface was built for. Per
 * direction (owner, 2026-09-27): "widgets or a widget page, notifications
 * and media controls, a quick-launch area for apps or recents, clock and
 * battery" -- an extension of the Android desktop, built from pieces
 * droidtop already has rather than a new system:
 *
 * - CompanionSystemBar and CompanionNotifications -- the SAME
 *   droidtop-styled clock/battery/network/controls and notification rows
 *   Gaming's companion already draws; there is no reason Standard needs a
 *   second implementation of either.
 * - A quick-launch row backed by RecentAppsStore (:shell-default's real,
 *   already-recorded recent/frequently-used app history), not a
 *   fabricated list.
 * - Already-added Android widgets, through the SAME CompanionWidgets host
 *   and CompanionWidgetPrefs set the companion's own widget picker
 *   manages -- one widget set across the whole companion experience,
 *   rather than a second add/remove UI this screen has no Activity result
 *   to run anyway (the same constraint CompanionSurface already documents
 *   for every non-owning host).
 *
 * "Media controls" and "plugin status tiles" are not separate elements
 * here: droidtop has no now-playing/media-session store to read from yet
 * (docs/SPEC.md 7e, scoped but not built -- nothing to show without
 * fabricating it), and a plugin status tile is an ordinary Android widget
 * (PluginStatusWidgetProvider), already covered by the widget area above.
 */
@Composable
internal fun StandardSecondScreenSurface() {
    val context = LocalContext.current
    val widgetManager = remember { AppWidgetManager.getInstance(context) }
    val widgetHost = remember { CompanionWidgets.host(context) }
    val widgetIds = remember { CompanionWidgetPrefs.widgetIds(context) }

    DisposableEffect(Unit) {
        CompanionWidgets.startListening(context)
        onDispose { CompanionWidgets.stopListening() }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            CompanionSystemBar()
            CompanionNotifications()
            QuickLaunchRow()
            widgetIds.forEach { widgetId ->
                val info = widgetManager.getAppWidgetInfo(widgetId)
                if (info != null) {
                    val density = LocalDensity.current
                    val widgetHeight = with(density) { maxOf(info.minHeight, 200).toDp() }
                    AndroidView(
                        factory = { c ->
                            widgetHost.createView(c.applicationContext, widgetId, info).apply {
                                setAppWidget(widgetId, info)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = widgetHeight).padding(vertical = 4.dp),
                    )
                }
            }
            if (widgetIds.isEmpty()) {
                Text(
                    "Add widgets from the companion screen in Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private data class QuickLaunchApp(val component: ComponentName, val label: String, val icon: Drawable)

/** Resolves label/icon off the main thread (PackageManager IPC), per component, guarded so one broken entry costs only itself. */
@Composable
private fun QuickLaunchRow() {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<QuickLaunchApp>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            RecentAppsStore.current(context).take(MAX_QUICK_LAUNCH).mapNotNull { component ->
                runCatching {
                    val info = pm.getActivityInfo(component, 0)
                    QuickLaunchApp(component, info.loadLabel(pm).toString(), info.loadIcon(pm))
                }.getOrNull()
            }
        }
    }
    if (apps.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Quick launch",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(apps, key = { it.component.flattenToString() }) { app -> QuickLaunchIcon(app) }
        }
    }
}

@Composable
private fun QuickLaunchIcon(app: QuickLaunchApp) {
    val context = LocalContext.current
    val bitmap = remember(app.component) { app.icon.toBitmap().asImageBitmap() }
    Column(
        modifier = Modifier.width(76.dp).clickable {
            runCatching {
                // On the screen the tap was on. Without an explicit display
                // a NEW_TASK launch from a non-Activity context resolves
                // against whichever display is ambiently current, which put
                // the app on the other screen (the same ambiguity
                // LaunchDisplay.startOn documents).
                val displayId = displayIdOf(context)
                context.startActivity(
                    Intent().setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                )
            }
        },
    ) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = app.label,
            modifier = Modifier.width(56.dp).height(56.dp).clip(RoundedCornerShape(12.dp)),
        )
        Text(
            app.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Suppress("DEPRECATION") // Display via WindowManager is the only route below API 30
private fun displayIdOf(context: android.content.Context): Int =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
    } else {
        context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.displayId
    }

// A row on a companion-sized panel; more than this just scrolls off anyway.
private const val MAX_QUICK_LAUNCH = 12

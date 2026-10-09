package dev.droidtop.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.launcher3.allapps.RecentAppsStore
import dev.droidtop.library.settings.CompanionHomeLayout
import dev.droidtop.library.settings.CompanionHomeSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recent apps on the companion Home (docs/SPEC.md "The companion's tabs", Droidtop/tracker#347): the apps
 * last opened from the launcher, from its own launch history ([RecentAppsStore], the drawer's Recent row),
 * one tap to open on this screen. It was Standard's own second-screen surface's quick-launch row; with
 * Standard drawing the companion tabs like every other mode it is a Home section, so a general-purpose app
 * row is there in every mode. Labels and icons are resolved off the main thread (PackageManager IPC), one
 * guarded lookup per app, so one broken entry costs only itself. Nothing recorded, no section.
 */
@Composable
internal fun CompanionAppsSection(layout: CompanionHomeLayout) {
    val context = LocalContext.current
    // The launcher records each launch in its own preferences file; re-read when it does, so an app opened from the
    // launcher shows up here while Home is on screen (it was read once, and Recent apps never appeared, #347).
    var launches by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences(
            com.android.launcher3.LauncherFiles.SHARED_PREFERENCES_KEY,
            android.content.Context.MODE_PRIVATE,
        )
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (RecentAppsStore.isRecentKey(key)) launches++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val apps by produceState(initialValue = emptyList<RecentApp>(), launches) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            RecentAppsStore.current(context).take(MAX_RECENT_APPS).mapNotNull { component ->
                runCatching {
                    val info = pm.getActivityInfo(component, 0)
                    RecentApp(component, info.loadLabel(pm).toString(), info.loadIcon(pm))
                }.getOrNull()
            }
        }
    }
    if (apps.isEmpty()) return
    CompanionHomeSectionFrame(CompanionHomeSection.APPS, layout, summary = apps.first().label) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            items(apps, key = { it.component.flattenToString() }) { app -> RecentAppTile(app) }
        }
    }
}

private data class RecentApp(val component: ComponentName, val label: String, val icon: Drawable)

@Composable
private fun RecentAppTile(app: RecentApp) {
    val context = LocalContext.current
    val bitmap = remember(app.component) { app.icon.toBitmap().asImageBitmap() }
    CompanionTile(
        onClick = {
            runCatching {
                // On the screen the tap was on. Without an explicit display a NEW_TASK launch from a
                // non-Activity context resolves against whichever display is ambiently current, which put
                // the app on the other screen (the same ambiguity LaunchDisplay.startOn documents).
                context.startActivity(
                    Intent().setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayIdOf(context)).toBundle(),
                )
            }
        },
        modifier = Modifier.width(76.dp),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
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
}

@Suppress("DEPRECATION") // Display via WindowManager is the only route below API 30
private fun displayIdOf(context: android.content.Context): Int =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
    } else {
        context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.displayId
    }

// A row on a companion-sized panel; more than this just scrolls off anyway.
private const val MAX_RECENT_APPS = 12

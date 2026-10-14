package dev.droidtop.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.launcher3.allapps.RecentAppsStore

/**
 * Apps and shortcuts pinned on the companion's Home (docs/SPEC.md "The companion's tabs", Apps; Droidtop/tracker#414
 * slice C20): a tap opens the app (or runs the shortcut) on the main screen, and "Here" opens it on the companion's
 * own screen, also a TalkBack action. Shortcuts come from Android's launcher API, which answers only while droidtop is
 * the home app (Standard), so without that no shortcut is offered.
 */
internal object AppPins {
    const val SHORTCUT_PREFIX = "shortcut:"

    fun shortcutId(packageName: String, id: String) = "$SHORTCUT_PREFIX$packageName/$id"

    /** (package, shortcut id) from a pin id, or null. Pure. */
    fun parseShortcut(pin: String): Pair<String, String>? =
        pin.takeIf { it.startsWith(SHORTCUT_PREFIX) }?.removePrefix(SHORTCUT_PREFIX)?.split('/', limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] }

    /** One launchable app: its package, its label, and whether Android files it as a game. */
    data class Launchable(val packageName: String, val label: String, val game: Boolean)

    /** Launchable apps by name, with their kind. Package manager work: off the main thread. */
    fun launchableApps(context: Context): List<Launchable> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .map { Launchable(it.activityInfo.packageName, it.loadLabel(pm).toString(), isGame(it.activityInfo.applicationInfo)) }
            .distinctBy { it.packageName }.filter { it.packageName != context.packageName }.sortedBy { it.label.lowercase() }
    }

    /** Launchable apps by name: (package, label), sorted. Package manager work: off the main thread. */
    fun launchable(context: Context): List<Pair<String, String>> = launchableApps(context).map { it.packageName to it.label }

    @Suppress("DEPRECATION") // FLAG_IS_GAME is how apps declared a game before Android 8's category
    private fun isGame(info: ApplicationInfo?): Boolean =
        info != null && (info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0)

    /** [packageName]'s shortcuts as (pin id, label), or empty when droidtop may not read them. Off the main thread. */
    fun shortcuts(context: Context, packageName: String): List<Pair<String, String>> {
        val apps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        if (!runCatching { apps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        return runCatching { apps.getShortcuts(query, Process.myUserHandle()).orEmpty() }.getOrDefault(emptyList())
            .map { shortcutId(packageName, it.id) to (it.shortLabel?.toString() ?: it.id) }
    }

    /** Opens an app or runs a shortcut pin on [displayId]; the plain reason when it could not. */
    fun open(context: Context, pin: String, displayId: Int?): String? {
        val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        parseShortcut(pin)?.let { (pkg, id) ->
            val apps = context.getSystemService(LauncherApps::class.java) ?: return "Shortcuts are not available"
            return runCatching { apps.startShortcut(pkg, id, null, options, Process.myUserHandle()); null }.getOrElse { "That shortcut is gone" }
        }
        val pkg = pin.removePrefix(dev.droidtop.library.settings.PinnedControls.APP_PREFIX)
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return "Not installed"
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            // The launcher's own history (the drawer's Recent row, Home's Recent apps, the picker's Recent): an app
            // opened from the companion is as recent as one opened from the drawer.
            launch.component?.let { RecentAppsStore.recordLaunch(context, it) }
            null
        }.getOrElse { "Android would not open it there" }
    }
}

/** One pinned app or shortcut as a Home tile: tap opens it on the main screen; Here opens it on this screen. */
@Composable
internal fun AppPinTile(pin: String, label: String, modifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    fun openMain() = AppPins.open(context, pin, ForegroundShell.current()?.window?.decorView?.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY)
    fun openHere() = AppPins.open(context, pin, view.display?.displayId)
    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(role = Role.Button) { openMain() }
            .semantics {
                contentDescription = "$label, opens on the main screen"
                customActions = listOf(CustomAccessibilityAction("Open on this screen") { openHere(); true })
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row { CompanionPill("Here") { openHere() } }
    }
}

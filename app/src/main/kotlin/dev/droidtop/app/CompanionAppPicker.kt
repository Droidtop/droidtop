package dev.droidtop.app

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.launcher3.allapps.RecentAppsStore
import dev.droidtop.library.settings.PinnedControls
import dev.droidtop.shell.gamepad.DroidtopKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pocketworkstation.pckeyboard.KeyboardSink
import java.util.Locale

/**
 * The companion's app picker (docs/SPEC.md "The companion's tabs", Apps; Droidtop/tracker#488 item 3): every launchable
 * app, games included, to open on the companion's own screen or on the main one. This is its pure part, tested without a
 * device: what the list shows for a search, with the pinned apps first and then the recent ones.
 */
internal object AppPicker {
    /** One launchable app. [game] is Android's own filing of it as a game. */
    data class App(val packageName: String, val label: String, val game: Boolean)

    data class Row(val app: App, val pinned: Boolean)

    /** A titled run of rows; the title is null for search results, which are one flat list. */
    data class Section(val title: String?, val rows: List<Row>)

    /** At most this many recent apps lead the list. */
    const val MAX_RECENT = 8

    /**
     * What the picker lists. With no [query]: Pinned (in pin order), Recent (most recent first, not pinned, at most
     * [MAX_RECENT]), then All apps (the rest, by name); a section with nothing in it is not listed. With a query: one list
     * of the apps it matches, best match first (the name starts with it, then a word of the name does, then the name
     * contains it, then the package does), ties broken pinned first, then recent, then by name. [gamesOnly] keeps only
     * games. [pinned] and [recent] are package names; ones that are not installed are ignored.
     */
    fun sections(apps: List<App>, pinned: List<String>, recent: List<String>, query: String, gamesOnly: Boolean = false): List<Section> {
        val shown = if (gamesOnly) apps.filter { it.game } else apps
        val byPackage = shown.associateBy { it.packageName }
        val pinnedSet = pinned.toSet()
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isNotEmpty()) {
            val recentRank = recent.withIndex().associate { it.value to it.index }
            val matches = shown.mapNotNull { app -> score(app, needle)?.let { app to it } }
            val ordered = matches.sortedWith(
                compareBy<Pair<App, Int>> { it.second }
                    .thenBy { if (it.first.packageName in pinnedSet) 0 else 1 }
                    .thenBy { recentRank[it.first.packageName] ?: Int.MAX_VALUE }
                    .thenBy { it.first.label.lowercase(Locale.ROOT) },
            ).map { Row(it.first, it.first.packageName in pinnedSet) }
            return if (ordered.isEmpty()) emptyList() else listOf(Section(null, ordered))
        }
        val pinnedRows = pinned.distinct().mapNotNull { byPackage[it] }.map { Row(it, true) }
        val recentRows = recent.distinct().filter { it !in pinnedSet }.mapNotNull { byPackage[it] }.take(MAX_RECENT).map { Row(it, false) }
        val shownAbove = (pinnedRows + recentRows).map { it.app.packageName }.toSet()
        val rest = shown.filter { it.packageName !in shownAbove }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .map { Row(it, false) }
        return listOf(
            Section("Pinned", pinnedRows),
            Section("Recent", recentRows),
            Section(if (shownAbove.isEmpty()) "All apps" else "All other apps", rest),
        ).filter { it.rows.isNotEmpty() }
    }

    /** How well [app] matches the lower-case [needle]: 0 best, null for no match. */
    fun score(app: App, needle: String): Int? {
        val label = app.label.lowercase(Locale.ROOT)
        return when {
            label.startsWith(needle) -> 0
            label.split(' ', '-', '_', '.', ':', '/').any { it.startsWith(needle) } -> 1
            label.contains(needle) -> 2
            app.packageName.lowercase(Locale.ROOT).contains(needle) -> 3
            else -> null
        }
    }

    /** The pinned apps' packages in pin order, from the stored pin ids. */
    fun pinnedPackages(pins: List<String>): List<String> =
        pins.filter { it.startsWith(PinnedControls.APP_PREFIX) }.map { it.removePrefix(PinnedControls.APP_PREFIX) }

    /** The line shown after a launch: where the app went, or why it did not open. */
    fun outcome(label: String, here: Boolean, failure: String?): String =
        failure?.let { "$label did not open: $it" } ?: if (here) "Opened $label on this screen" else "Opened $label on the main screen"
}

/** App icons, drawn small, decoded off the main thread once and kept (the list scrolls, so rows come and go). */
private object PickerIcons {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun cached(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()
        }
        if (bitmap != null) cache.put(packageName, bitmap)
        return bitmap
    }

    private const val ICON_PX = 96
}

/**
 * The picker page of the companion's Apps tab: a search field typed with droidtop's own keyboard, a Games filter, the
 * list (pinned, recent, the rest) with icons, and for each app Open here (the row's tap, on this screen), Main screen
 * and Pin. Package manager and preferences reads happen off the main thread; the list is lazy, so a library of hundreds
 * of apps costs the rows on screen.
 */
@Composable
internal fun CompanionAppPicker(onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    val pinState by PinnedControls.pins.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PinnedControls.load(context.applicationContext) } }
    val launches by rememberRecentLaunchCount()
    val apps by produceState(emptyList<AppPicker.App>()) {
        value = withContext(Dispatchers.IO) {
            AppPins.launchableApps(context.applicationContext).map { AppPicker.App(it.packageName, it.label, it.game) }
        }
    }
    val recent by produceState(emptyList<String>(), launches) {
        value = withContext(Dispatchers.IO) { RecentAppsStore.current(context.applicationContext).map { it.packageName } }
    }
    var draft by remember { mutableStateOf(CompanionDraft()) }
    var keyboard by remember { mutableStateOf(false) }
    var gamesOnly by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val pinned = AppPicker.pinnedPackages(pinState.pins)
    val sections = remember(apps, pinned, recent, draft.text, gamesOnly) {
        AppPicker.sections(apps, pinned, recent, draft.text, gamesOnly)
    }

    fun open(app: AppPicker.App, here: Boolean) {
        val mainDisplay = ForegroundShell.current()?.window?.decorView?.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        val target = if (here) view.display?.displayId else mainDisplay
        result = AppPicker.outcome(app.label, here, AppPins.open(context, PinnedControls.appId(app.packageName), target))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompanionPill("Back", onClick = onClose)
            Text(
                "Open an app",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            CompanionPill("Games", selected = gamesOnly) { gamesOnly = !gamesOnly }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DraftField(
                draft = draft,
                modifier = Modifier.weight(1f),
                onTap = { offset ->
                    draft = draft.placeCursor(offset)
                    keyboard = true
                },
                placeholder = "Search apps",
            )
            if (draft.text.isNotEmpty()) CompanionPill("Clear") { draft = CompanionDraft() }
            CompanionPill(if (keyboard) "Hide" else "Keys") { keyboard = !keyboard }
        }
        result?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                apps.isEmpty() -> CompanionNote("Reading the apps on this device")
                sections.isEmpty() -> CompanionNote(
                    if (draft.text.isBlank()) "No games on this device" else "No app matches \"${draft.text.trim()}\"",
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sections.forEach { section ->
                        section.title?.let { title ->
                            item(key = "h:$title") {
                                Text(
                                    title.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                                )
                            }
                        }
                        items(section.rows, key = { "${section.title}:${it.app.packageName}" }) { row ->
                            PickerRow(
                                row = row,
                                onHere = { open(row.app, here = true) },
                                onMain = { open(row.app, here = false) },
                                onPin = { PinnedControls.setPinned(context.applicationContext, PinnedControls.appId(row.app.packageName), !row.pinned) },
                            )
                        }
                    }
                }
            }
        }
        if (keyboard) {
            DroidtopKeyboard(
                sink = object : KeyboardSink {
                    override fun key(androidKeyCode: Int, down: Boolean) {
                        val (next, submit) = draft.key(androidKeyCode, down, ::virtualChar)
                        draft = next
                        if (submit) keyboard = false
                    }

                    override fun text(chars: CharSequence) {
                        draft = draft.insert(chars)
                    }
                },
            )
        }
    }
}

@Composable
private fun PickerRow(row: AppPicker.Row, onHere: () -> Unit, onMain: () -> Unit, onPin: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val packageName = row.app.packageName
    val icon by produceState(PickerIcons.cached(packageName), packageName) { value = PickerIcons.load(context, packageName) }
    CompanionTile(
        onClick = onHere,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${row.app.label}, opens on this screen"
                customActions = listOf(
                    CustomAccessibilityAction("Open on the main screen") { onMain(); true },
                    CustomAccessibilityAction(if (row.pinned) "Unpin from Home" else "Pin to Home") { onPin(); true },
                )
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().background(colors.surface).heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Image(painter = BitmapPainter(bitmap), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(row.app.label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.app.game) Text("Game", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            CompanionPill("Main screen", onClick = onMain)
            CompanionPill(if (row.pinned) "Unpin" else "Pin", selected = row.pinned, onClick = onPin)
        }
    }
}

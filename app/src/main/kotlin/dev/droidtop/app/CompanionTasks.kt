package dev.droidtop.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.tasks.AppsInsights
import dev.droidtop.runtime.tasks.CloseOutcome
import dev.droidtop.runtime.tasks.ProtectedApps
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.SharedRunningAppsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The line under an app on the Apps tab: the sorted figure ("12% processor", "180 MB", "24 MB data"), "Used data
 * lately", and the sensitive permissions it holds. Pure.
 */
internal fun appFacts(sort: AppsInsights.Sort, figure: Double?, usedData: Boolean, permissions: List<String>): String? = listOfNotNull(
    figure?.let {
        when (sort) {
            AppsInsights.Sort.CPU -> "%.0f%% processor".format(it)
            AppsInsights.Sort.MEMORY -> "${(it / 1024).toInt()} MB"
            AppsInsights.Sort.DATA -> "${(it / 1_000_000).toInt()} MB data"
            AppsInsights.Sort.RECENT -> null
        }
    },
    if (usedData && sort != AppsInsights.Sort.DATA) "Used data lately" else null,
    permissions.takeIf { it.isNotEmpty() }?.joinToString(", "),
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/**
 * Touch-only companion view of the shared running-app list (docs/SPEC.md "The companion's tabs", Apps). Clear all
 * asks first, with the safe answer first, and leaves alone what is protected and whatever is open on this
 * companion's own screen; each row's Protect keeps its app from Clear all (Droidtop/tracker#252).
 */
@Composable
internal fun CompanionTasks() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snapshot by TaskManager.snapshot.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }
    // Per-row state in the row's own subtitle: "Closing…", then the row goes, or a short result stays.
    val rowStates = remember { mutableStateMapOf<String, String>() }
    var pending by remember { mutableStateOf<List<RunningApp>?>(null) }
    var protectedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { protectedApps = withContext(Dispatchers.IO) { ProtectedApps.get(context) } }
    LaunchedEffect(Unit) { TaskManager.watch(context) }
    val displays = remember { TaskManager.displayIds(context) }
    // Sort and the figures beside each row (slice C20): processor or memory with the helper app, data with Usage
    // access, read off the main thread when the sort changes and as the list changes; permissions per app.
    var sort by remember { mutableStateOf(AppsInsights.Sort.RECENT) }
    val running = snapshot?.apps.orEmpty()
    val names = running.map { it.packageName }
    val metric by produceState<Map<String, Double>?>(null, sort, names) {
        value = withContext(Dispatchers.IO) {
            when (sort) {
                AppsInsights.Sort.RECENT -> emptyMap()
                AppsInsights.Sort.CPU -> AppsInsights.cpu()
                AppsInsights.Sort.MEMORY -> AppsInsights.memory()
                AppsInsights.Sort.DATA -> AppsInsights.data(context.applicationContext)
            }
        }
    }
    val data by produceState<Map<String, Double>?>(null, names) { value = withContext(Dispatchers.IO) { AppsInsights.data(context.applicationContext) } }
    val permissions by produceState<Map<String, List<String>>>(emptyMap(), names) {
        value = withContext(Dispatchers.IO) { names.associateWith { AppsInsights.sensitive(context.applicationContext, it) } }
    }
    val provider by produceState(false) { value = withContext(Dispatchers.IO) { runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false) } }
    val apps = AppsInsights.sort(running, sort, metric.orEmpty())
    // Stop asks first while Ask before stopping is on (and always in Kid and Kiosk); without a helper a stop that
    // Android may ignore offers App info's Force stop.
    var stopping by remember { mutableStateOf<RunningApp?>(null) }
    var forceStopIn by remember { mutableStateOf<String?>(null) }
    fun stop(app: RunningApp) {
        stopping = null
        rowStates[app.packageName] = "Closing…"
        scope.launch {
            val outcome = TaskManager.close(context, app.packageName)
            if (outcome is CloseOutcome.Closed) {
                rowStates.remove(app.packageName)
            } else {
                rowStates[app.packageName] = outcome.text
                forceStopIn = app.packageName
            }
        }
    }

    fun clearAll(targets: List<RunningApp>) {
        pending = null
        detail = "Closing…"
        scope.launch { detail = TaskManager.clearAll(context, targets).message }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState())) {
            AppsInsights.Sort.entries.filter { it == AppsInsights.Sort.RECENT || it == AppsInsights.Sort.DATA || provider }.forEach { option ->
                CompanionPill(option.label, selected = sort == option) { sort = option }
            }
        }
        if (sort == AppsInsights.Sort.DATA && metric == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CompanionNote("Data use needs Usage access")
                CompanionPill("Allow") {
                    dev.droidtop.runtime.systemstatus.SettingsLaunch.start(context, android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        if (snapshot == null) {
            Text("Reading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (apps.isEmpty()) {
            Text("Nothing running", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SharedRunningAppsList(
                apps = apps,
                displays = displays,
                rowMessage = { i ->
                    apps.getOrNull(i)?.let { app ->
                        rowStates[app.packageName] ?: appFacts(
                            sort = sort,
                            figure = metric?.get(app.packageName),
                            usedData = AppsInsights.usedData(data?.get(app.packageName)),
                            permissions = permissions[app.packageName].orEmpty(),
                        )
                    }
                },
                protectedPackages = protectedApps,
                onProtect = { _, app ->
                    val protect = app.packageName !in protectedApps
                    protectedApps = if (protect) protectedApps + app.packageName else protectedApps - app.packageName
                    scope.launch(Dispatchers.IO) { ProtectedApps.set(context, app.packageName, protect) }
                },
                onClear = {
                    // The companion's own screen keeps what was opened on it.
                    val own = setOfNotNull(view.display?.displayId)
                    scope.launch {
                        val targets = TaskManager.clearAllTargets(context, keepDisplays = own)
                        val asks = dev.droidtop.library.settings.GameControls.asksStop(
                            dev.droidtop.library.settings.UiModeRefresh.mode.value,
                            dev.droidtop.library.settings.CompanionPrefs.settings.value.ask,
                        )
                        when {
                            targets.isEmpty() -> detail = "Nothing to close"
                            asks -> pending = targets
                            else -> clearAll(targets)
                        }
                    }
                },
                onSwitch = { _, app -> detail = TaskActions.bringTo(context, app.packageName, app.displayId) },
                onClose = { _, app ->
                    val asks = dev.droidtop.library.settings.GameControls.asksStop(
                        dev.droidtop.library.settings.UiModeRefresh.mode.value,
                        dev.droidtop.library.settings.CompanionPrefs.settings.value.ask,
                    )
                    if (asks) stopping = app else stop(app)
                },
                onMove = { _, app ->
                    val other = displays.firstOrNull { it != app.displayId }
                    if (other != null) detail = TaskActions.bringTo(context, app.packageName, other)
                },
            )
        }
        stopping?.let { app ->
            Text(
                "Stop ${app.label}? Anything not saved in it is lost.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(top = 8.dp)) {
                CompanionPill("Keep it", selected = true) { stopping = null }
                CompanionPill("Stop") { stop(app) }
            }
        }
        forceStopIn?.let { pkg ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                CompanionPill("Force stop in App info") {
                    forceStopIn = null
                    dev.droidtop.runtime.systemstatus.SettingsLaunch.start(
                        context,
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        if (data?.values?.any { AppsInsights.usedData(it) } == true) {
            CompanionPill("Data saver") {
                dev.droidtop.runtime.systemstatus.SettingsLaunch.start(
                    context,
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        pending?.let { targets ->
            Text(
                if (targets.size == 1) "Close ${targets.first().label}?" else "Close ${targets.size} apps?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            // The safe answer first, the two well apart.
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(top = 8.dp)) {
                CompanionPill("Keep them", selected = true) { pending = null }
                CompanionPill("Close") { clearAll(targets) }
            }
        }
        (detail ?: if (snapshot?.note != null) "Limited list" else null)?.let { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

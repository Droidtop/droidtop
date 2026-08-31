package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.HandheldSettingsCatalog
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap

/**
 * The Quick Menu: press R2 anywhere in the Handheld shell (docs/
 * SPEC.md §4, quick-menu paradigm). A right-edge sheet in the Steam
 * Deck QAM family — the paradigm survey that picked it is in the SPEC:
 * the Deck's quick access menu (dedicated button, right sheet, vertical
 * tabs) is the strongest prior art for glanceable-while-playing, iiSU's
 * trigger menu is the same family on Android handhelds, and a
 * dedicated button here is R2, named by the R2 pill in the shell's
 * top-right corner. Hold-SELECT remains only as the fallback for pads
 * whose triggers are analog-only and never emit an R2 key event
 * (short-press SELECT keeps its existing meaning; chords were rejected
 * as undiscoverable).
 *
 * ENTIRELY controller-driven, per direction: L1/R1 switch tabs, D-pad
 * moves, A opens, X dismisses, Y clears all, B closes. The System tab
 * is the settings catalog's own System group rendered through the same
 * [CatalogNavigator] the Settings section uses — one mechanism, not a
 * second quick-settings implementation.
 *
 * A Compose [Dialog] on purpose: its window owns input while open, so
 * modality costs no key-event fencing in the shell underneath.
 */
@Composable
internal fun QuickMenu(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var tab by remember { mutableStateOf(QuickTab.NOTIFICATIONS) }

        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .align(Alignment.CenterEnd)
                    // Preview, not plain onKeyEvent: the System tab's
                    // CatalogNavigator holds focus and handles its own
                    // keys, and tab switching must win over it -- a
                    // parent's PREVIEW pass runs before the child sees
                    // the event at all.
                    .onPreviewKeyEvent { event ->
                        val action = GamepadKeyMap.actionFor(event.key)
                        // R2 toggles: a FRESH KeyDown closes. The
                        // opening press's own key-up lands in this
                        // window once it takes focus, so R2 KeyUp is
                        // swallowed, never acted on -- the same
                        // flash-open-shut hazard the SELECT note below
                        // describes.
                        if (action == GamepadAction.R2) {
                            if (event.type == KeyEventType.KeyDown) onDismiss()
                            return@onPreviewKeyEvent true
                        }
                        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        when (action) {
                            GamepadAction.L -> {
                                tab = tab.previous(); true
                            }
                            GamepadAction.R -> {
                                tab = tab.next(); true
                            }
                            // SELECT deliberately does NOT close: the
                            // opening hold's own key-up can land in this
                            // window once it takes focus, and closing on
                            // it would make the menu flash open-shut. B
                            // closes.
                            else -> false
                        }
                    },
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuickTab.entries.forEach { t ->
                            Text(
                                t.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (t == tab) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "L1 / R1",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.padding(4.dp))
                    when (tab) {
                        QuickTab.NOTIFICATIONS -> NotificationsTab(onDismiss)
                        QuickTab.SYSTEM -> SystemTab(onDismiss)
                    }
                }
            }
        }
    }
}

private enum class QuickTab(val label: String) {
    NOTIFICATIONS("Notifications"),
    SYSTEM("System");

    fun next() = entries[(ordinal + 1) % entries.size]
    fun previous() = entries[(ordinal - 1 + entries.size) % entries.size]
}

@Composable
private fun SystemTab(onDismiss: () -> Unit) {
    // The settings catalog's own System group, through the same
    // navigator the Settings section uses. Filtering by group id keeps
    // this a VIEW of that group, never a copy that drifts.
    val root = remember {
        CatalogScreen(
            id = "quick_system",
            title = "System",
            groups = { ctx ->
                HandheldSettingsCatalog.groups(ctx)
                    .filter { it.id == HandheldSettingsCatalog.GROUP_SYSTEM }
            },
        )
    }
    CatalogNavigator(root = root, onExit = onDismiss)
}

@Composable
private fun NotificationsTab(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted = remember { NotificationsStore.isGranted(context) }
    val items by NotificationsStore.items.collectAsState()
    var focusIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(focusIndex, items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(focusIndex.coerceIn(0, items.size - 1))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                val action = GamepadKeyMap.actionFor(event.key)
                val current = items.getOrNull(focusIndex)
                when {
                    action == GamepadAction.BACK || action == GamepadAction.B -> {
                        onDismiss(); true
                    }
                    action == GamepadAction.UP && items.isNotEmpty() -> {
                        focusIndex = (focusIndex - 1 + items.size) % items.size; true
                    }
                    action == GamepadAction.DOWN && items.isNotEmpty() -> {
                        focusIndex = (focusIndex + 1) % items.size; true
                    }
                    action == GamepadAction.A && !granted -> {
                        context.startActivity(NotificationsStore.grantIntent()); onDismiss(); true
                    }
                    action == GamepadAction.A && current != null -> {
                        // Captured locally: contentIntent is a property
                        // from another module, so no smart cast.
                        val pending = current.contentIntent
                        if (pending != null) runCatching { pending.send() }
                        onDismiss(); true
                    }
                    action == GamepadAction.X && current?.clearable == true -> {
                        NotificationsStore.controller?.dismiss(current.key); true
                    }
                    action == GamepadAction.Y && items.any { it.clearable } -> {
                        NotificationsStore.controller?.clearAll(); true
                    }
                    else -> false
                }
            },
    ) {
        when {
            !granted -> Text(
                "droidtop needs notification access to show these.\n\nPress A to open the grant screen -- it is a one-time system permission.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            items.isEmpty() -> Text(
                "No notifications.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    val focused = index == focusIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (focused) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .padding(10.dp),
                    ) {
                        Row {
                            Text(
                                item.appLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            if (!item.clearable) {
                                Text(
                                    "ongoing",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (item.text.isNotBlank()) {
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
        Text(
            if (granted) "A Open   X Dismiss   Y Clear all   B Close" else "A Grant access   B Close",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

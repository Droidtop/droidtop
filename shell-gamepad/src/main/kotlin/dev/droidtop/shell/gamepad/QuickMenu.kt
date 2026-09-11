package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * is Android's quick-settings shape (status header, brightness and
 * volume sliders, a grid of large tiles -- see [QuickSettingsPanel]),
 * and it is still a VIEW of the settings catalog's own System group,
 * never a second quick-settings implementation with its own values: the
 * tiles carry the catalog's items and every press goes back to the
 * item's own write path.
 *
 * WHERE the sheet sits follows the shape of the screen, because the
 * reason it is an edge sheet is that it must not cover the shell behind
 * it. On a landscape screen that edge is the right one, the Steam Deck
 * QAM shape, sized by what the tile grid needs. On a screen held
 * upright, a full-height right-edge sheet is the whole screen, so it
 * becomes a BOTTOM sheet instead: full width, sized by its content, the
 * shell still visible above it and the tabs within thumb reach rather
 * than at the far top corner. Same sheet, same tabs, same contents,
 * measured differently.
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

        val window = currentShellWindow()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Wide enough for a real tile grid (two columns always, three
            // when the screen has room), capped so the sheet stays a
            // sheet -- the shell behind it must remain visible, which is
            // the whole point of a quick menu over a settings screen.
            val sheetWidth = if (window.portrait) {
                maxWidth
            } else {
                (maxWidth * 0.62f).coerceIn(480.dp, 760.dp).coerceAtMost(maxWidth)
            }
            Surface(
                modifier = Modifier
                    .then(
                        if (window.portrait) {
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxHeight * 0.72f)
                        } else {
                            Modifier.fillMaxHeight().width(sheetWidth)
                        },
                    )
                    .align(if (window.portrait) Alignment.BottomCenter else Alignment.CenterEnd)
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
                // The shell's own overlay surface, not the platform's
                // colour scheme. Every token this sheet's contents draw
                // with (MenuTokens: white label text, a 5%-white row
                // fill) is defined against THIS surface; painting the
                // sheet with MaterialTheme.colorScheme.surface meant a
                // device in a light colour state got a white panel with
                // white-on-white tile labels, the System tab's own
                // contents rendered illegible by a background the rest
                // of the shell never uses (emulator rig, 2026-09-10).
                color = MenuTokens.OverlaySurface,
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .then(if (window.portrait) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuickTab.entries.forEach { t ->
                            Text(
                                t.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (t == tab) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
                                // The tabs were nameplates: L1/R1 switched
                                // them and a tap did nothing, so on a phone
                                // the System tab was unreachable.
                                modifier = Modifier
                                    .clickable { tab = t }
                                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Closing is B on a pad and had no touch route at
                        // all; dismissing by tapping outside is not
                        // discoverable and is not available at all when the
                        // sheet is full width.
                        Text(
                            "Close",
                            style = MaterialTheme.typography.labelLarge,
                            color = MenuTokens.OnSurfaceMuted,
                            modifier = Modifier
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.padding(4.dp))
                    when (tab) {
                        QuickTab.NOTIFICATIONS -> NotificationsTab(onDismiss)
                        QuickTab.SYSTEM -> QuickSettingsPanel(sheetWidth.value.toInt(), onDismiss)
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
private fun NotificationsTab(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted = remember { NotificationsStore.isGranted(context) }
    val items by NotificationsStore.items.collectAsState()
    var focusIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    // Every action below is defined once, in the key handler. A tap
    // moves the cursor and sends the real press rather than repeating
    // any of it.
    val press = rememberGamepadTouch()

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
                color = MenuTokens.OnSurface,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            items.isEmpty() -> Text(
                "No notifications.",
                style = MaterialTheme.typography.bodyMedium,
                color = MenuTokens.OnSurfaceMuted,
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
                            .background(if (focused) MenuTokens.SurfaceSelected else MenuTokens.Surface)
                            // Without this a notification could only be
                            // reached with a pad: the rows carried no
                            // touch route at all, in the one sheet a
                            // phone user opens most. After the
                            // background, so the press indication is
                            // drawn over it rather than under it.
                            .clickable {
                                focusIndex = index
                                press(GamepadAction.A)
                            }
                            .padding(10.dp),
                    ) {
                        Row {
                            Text(
                                item.appLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MenuTokens.OnSurfaceMuted,
                            )
                            Spacer(Modifier.weight(1f))
                            if (!item.clearable) {
                                Text(
                                    "ongoing",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MenuTokens.Placeholder,
                                )
                            }
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MenuTokens.OnSurface,
                        )
                        if (item.text.isNotBlank()) {
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MenuTokens.Value,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
        // The legend here named X (dismiss one) and Y (clear all),
        // neither of which had any touch route. As a hint bar the same
        // line IS the route, dispatching into this dialog's own window.
        TouchHintBar(
            hints = if (granted) {
                listOf(
                    GamepadAction.A to "Open",
                    GamepadAction.X to "Dismiss",
                    GamepadAction.Y to "Clear all",
                    GamepadAction.B to "Close",
                )
            } else {
                listOf(GamepadAction.A to "Grant access", GamepadAction.B to "Close")
            },
            background = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

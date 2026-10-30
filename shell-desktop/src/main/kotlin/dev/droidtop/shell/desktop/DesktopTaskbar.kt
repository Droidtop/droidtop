package dev.droidtop.shell.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.DesktopWindows
import dev.droidtop.hostbridge.HiddenWindow
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.hostbridge.SwayWindow
import dev.droidtop.hostbridge.Toplevel
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.input.InputSeats
import dev.droidtop.input.PointerTransform
import dev.droidtop.library.Library
import dev.droidtop.library.LaunchResult
import dev.droidtop.library.LibraryKinds
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.runtime.DesktopLaunchRequests
import dev.droidtop.runtime.DesktopScale
import dev.droidtop.runtime.DisplayOutput
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.integrations.PluginHub
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.library.settings.DesktopPrefs
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.PcGameStandalone
import dev.droidtop.shell.gamepad.pc.PcLaunchOfferSheet
import dev.droidtop.library.TaskbarPin
import dev.droidtop.library.TaskbarPins
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.runtime.tasks.CloseOutcome
import dev.droidtop.runtime.tasks.OwnScreen
import dev.droidtop.runtime.tasks.OwnScreens
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.TaskPolicy
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.AppIcon
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.ActionNote
import dev.droidtop.shell.gamepad.ActionRow
import dev.droidtop.shell.gamepad.ActionSheet
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.StatusCluster
import dev.droidtop.shell.gamepad.hosted.HostedArt
import dev.droidtop.shell.gamepad.query.LauncherSearchApp
import dev.droidtop.shell.gamepad.query.LauncherSearchScreen
import dev.droidtop.library.StartMenuSections
import dev.droidtop.shell.gamepad.hosted.HostedListSheet
import dev.droidtop.shell.gamepad.hosted.HostedRow
import dev.droidtop.shell.gamepad.hosted.PadLegend
import dev.droidtop.shell.gamepad.hosted.QuickMenuStandalone
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.pc.rememberPcLaunch

// Desktop mode's taskbar (Droidtop/tracker#764, split out of DesktopShell.kt): its buttons, window list, pins,
// Android tasks, tray and plugin items, and the system screens its buttons open.

@Composable
internal fun BoxScope.Taskbar(
    atTop: Boolean,
    onHeight: (Int) -> Unit,
    hostBridge: HostBridge?,
    toplevels: List<Toplevel>,
    androidApps: List<RunningApp>,
    pins: List<TaskbarPin>,
    onOpenPin: (TaskbarPin) -> Unit,
    onUnpin: (TaskbarPin) -> Unit,
    windows: DesktopWindows?,
    onOpenStartMenu: () -> Unit,
    windowIcons: Map<String, String> = emptyMap(),
    onOpenQuickMenu: () -> Unit,
) {
    val context = LocalContext.current
    // With a pad in hand the bar names the four buttons that reach droidtop's chrome (the activity
    // answers them, see DesktopPadRoutes), on a strip of its own so the bar keeps its width for the
    // window list on a 768 dp console.
    val padPresent = currentShellWindow().padPresent
    val legend: @Composable () -> Unit = {
        if (padPresent) {
            PadLegend(
                items = listOf(
                    GamepadAction.START to "Start menu",
                    GamepadAction.R2 to "Quick menu",
                    GamepadAction.L to "Windows",
                    GamepadAction.R to "Search",
                ),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }

    val ownScreens by OwnScreens.open.collectAsState()
    // On a narrow screen the buttons alone fill the bar, and a Row gives its weighted child only what is left: the
    // window list had no width at all on a 411 dp phone, so foot, Enginehost and Settings were open and nowhere on
    // the bar (Droidtop/tracker#750, rig 2638). Below [BAR_ONE_ROW_MIN] the pins and windows take a strip of their own.
    BoxWithConstraints(
        modifier = Modifier
            .align(if (atTop) Alignment.TopStart else Alignment.BottomStart)
            .fillMaxWidth(),
    ) {
        val oneRow = maxWidth >= BAR_ONE_ROW_MIN
        val strip: @Composable () -> Unit = {
            if (!oneRow && (pins.isNotEmpty() || toplevels.isNotEmpty() || androidApps.isNotEmpty() || ownScreens.isNotEmpty())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(dev.droidtop.shell.gamepad.MenuTokens.Ground),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TaskbarPinChips(pins, onOpenPin, onUnpin)
                    TaskbarWindowList(hostBridge, toplevels, androidApps, ownScreens, windows, windowIcons, modifier = Modifier.weight(1f))
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onHeight(it.height) },
        ) {
            if (!atTop) {
                legend()
                strip()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(dev.droidtop.shell.gamepad.MenuTokens.Ground),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskbarButton(onClick = onOpenStartMenu) {
                    Text(stringResource(R.string.desktop_start_menu))
                }
                Spacer(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                if (oneRow) {
                    TaskbarPinChips(pins, onOpenPin, onUnpin)
                    TaskbarWindowList(hostBridge, toplevels, androidApps, ownScreens, windows, windowIcons, modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                // What only Android can do stays on droidtop's bar (Droidtop/tracker#555 D4): Terminal, Containers
                // and Settings are Start's System rows, and the clock is the panel's (Waybar's custom/clock, or
                // swaybar's status line), so the screen has one. The mode switcher stays, by name: Desktop's only
                // other route to the Android home or Gaming is a long-press of Back (SPEC 2c).
                TaskbarButton(onClick = { openModes(context) }) {
                    Text(stringResource(R.string.desktop_modes))
                }
                KeyboardTaskbarItem()
                PluginTaskbarItems()
                CompanionTaskbarItem()
                ClipboardNotice()
                SystemTray(onClick = onOpenQuickMenu)
            }
            if (atTop) {
                strip()
                legend()
            }
        }
    }
}

/** The narrowest bar that keeps the pins and the window list beside the buttons (docs/SPEC.md 2b). */
internal val BAR_ONE_ROW_MIN = 600.dp

/**
 * A taskbar button with the narrow padding a bar needs. The stock Button's
 * 24dp of content padding and 8dp of margin per side left the window list
 * (the weighted slot beside them) zero width on a 1080p tablet-class display,
 * so no window row could show even with windows open (Droidtop/tracker#94).
 */
@Composable
internal fun TaskbarButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    // A flat chip in the Gaming palette, not Material's filled pill (Droidtop/tracker#555 D4).
    androidx.compose.material3.Surface(
        onClick = onClick,
        // 48 dp, the touch target every one of droidtop's controls keeps (DESIGN-LANGUAGE, Droidtop/tracker#690).
        modifier = Modifier.padding(horizontal = 3.dp).heightIn(min = 48.dp),
        shape = dev.droidtop.shell.gamepad.MenuTokens.RowShape,
        color = dev.droidtop.shell.gamepad.MenuTokens.Card,
        contentColor = dev.droidtop.shell.gamepad.MenuTokens.OnSurface,
    ) {
        Box(modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * The taskbar's real cross-container task manager (docs/SPEC.md's Desktop
 * shell section, Droidtop/tracker#94): one row per toplevel host-bridge
 * reports over wlr-foreign-toplevel-management. Tap activates it (raises +
 * focuses, restoring it first when minimized); tapping the already-activated
 * one minimizes it instead, so a single action both switches windows and
 * gets one out of the way, the same "click its taskbar button again"
 * convention as a desktop OS. Long-press opens a small menu for
 * Restore/Minimize and Close -- the destructive one deliberately not on a
 * plain tap.
 *
 * Minimize and restore go through [DesktopWindows], which knows the
 * compositor: labwc honours the protocol's set_minimized, sway ignores it
 * (rig, fix2-t5.png in verify-2026-09-29, Droidtop/tracker#145) and gets its
 * scratchpad over IPC instead (Droidtop/tracker#391). A minimize the
 * compositor could not be asked for says so in a toast.
 *
 * Empty (no row shown at all, not even a placeholder) when [hostBridge] is
 * null or the compositor never advertised the protocol: an empty window
 * list and "there is no session"/"the protocol isn't there" look the same
 * to a user with nothing open, and this row isn't where either gets
 * explained.
 *
 * "Move to another output" (an earlier draft of docs/SPEC.md's Desktop
 * section) is NOT built here: wlr-foreign-toplevel-management-unstable-v1
 * has no such request (only activate/set_minimized/unset_minimized/close/
 * set_fullscreen/set_rectangle -- checked against the protocol XML), and
 * host-bridge's own output handling is still single-output-only (see
 * wayland_client.cpp's WaylandGlobals comment). Real "move to output" needs
 * real multi-output support first; SPEC.md is corrected alongside this
 * change to stop claiming it as already built.
 */
@Composable
internal fun TaskbarWindowList(
    hostBridge: HostBridge?,
    toplevels: List<Toplevel>,
    androidApps: List<RunningApp>,
    ownScreens: List<OwnScreen>,
    windows: DesktopWindows?,
    windowIcons: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hidden by rememberHiddenWindows(windows)
    val placed by remember(windows) { windows?.placed ?: MutableStateFlow(emptyMap<Long, SwayWindow>()) }.collectAsState()

    if (toplevels.isEmpty() && androidApps.isEmpty() && ownScreens.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }

    LazyRow(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        items(toplevels, key = { it.id }) { toplevel ->
            val minimized = windows?.isMinimized(toplevel, hidden) ?: toplevel.minimized
            TaskbarWindowRow(
                toplevel = toplevel,
                icon = windowIcons[toplevel.appId.lowercase()],
                minimized = minimized,
                onTap = {
                    scope.launch {
                        if (toplevel.activated && !minimized) minimizeWindow(context, windows, toplevel) else windows?.raise(toplevel)
                    }
                },
                onMinimize = {
                    scope.launch { if (minimized) windows?.raise(toplevel) else minimizeWindow(context, windows, toplevel) }
                },
                onClose = { hostBridge?.closeToplevel(toplevel.id) },
                placed = toplevel.id in placed,
                onPlace = windows?.takeIf { it.canPlace }?.let { w ->
                    { placement: DesktopWindows.Placement ->
                        scope.launch {
                            if (!w.place(toplevel, placement)) {
                                dev.droidtop.library.settings.Notices.flash("Couldn't move this window", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context)
                            }
                        }
                        Unit
                    }
                },
            )
        }
        // The Android apps and games started from here, after the container's windows (Droidtop/tracker#352).
        items(androidApps, key = { "app:" + it.packageName + "/" + it.displayId }) { app ->
            TaskbarAppRow(
                app = app,
                onOpen = { openAndroidApp(context, app) },
                onClose = { scope.launch { closeAndroidApp(context, app) } },
            )
        }
        // droidtop's own screens open in a task of their own: Settings, a place (Droidtop/tracker#750).
        items(ownScreens, key = { "own:" + it.taskId }) { screen ->
            TaskbarOwnScreenRow(
                screen = screen,
                onOpen = { openOwnScreen(context, screen) },
                onClose = { OwnScreens.close(context, screen) },
            )
        }
    }
}

/** One of droidtop's own screens in the bar: droidtop's icon and the screen's name; a tap brings it back, a long press offers Close. */
@Composable
internal fun TaskbarOwnScreenRow(screen: OwnScreen, onOpen: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .widthIn(max = 180.dp)
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp),
        ) {
            AppIcon(context.packageName, size = 20.dp)
            Text(screen.title, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menuOpen) {
            val close = stringResource(R.string.desktop_close)
            ActionSheet(
                title = screen.title,
                rows = listOf(ActionRow(close, onSelect = { menuOpen = false; onClose() })),
                onDismiss = { menuOpen = false },
            )
        }
    }
}

/** [windows]' minimized-by-droidtop set as state; empty with no session. */
@Composable
internal fun rememberHiddenWindows(windows: DesktopWindows?) =
    remember(windows) { windows?.hidden ?: MutableStateFlow(emptyMap<Long, HiddenWindow>()) }.collectAsState()

/** Minimizes [toplevel], and says so when the compositor could not be asked. */
internal suspend fun minimizeWindow(context: Context, windows: DesktopWindows?, toplevel: Toplevel) {
    if (windows?.minimize(toplevel) != true) {
        dev.droidtop.library.settings.Notices.flash("Couldn't minimize this window", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context)
    }
}

@Composable
internal fun TaskbarWindowRow(
    toplevel: Toplevel,
    icon: String?,
    minimized: Boolean,
    onTap: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    placed: Boolean = false,
    onPlace: ((DesktopWindows.Placement) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        // The app's icon ahead of the title, so windows of different programs tell apart at a glance (#689).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 180.dp)
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onTap, onLongClick = { menuOpen = true })
                .background(
                    if (minimized) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .padding(horizontal = 10.dp),
        ) {
            if (icon != null) HostedArt(icon, modifier = Modifier.padding(end = 6.dp), size = 20.dp)
            Text(
                windowTitle(toplevel),
                color = if (toplevel.activated) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (toplevel.activated) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menuOpen) {
            val maximize = stringResource(R.string.desktop_maximize)
            val snapLeft = stringResource(R.string.desktop_snap_left)
            val snapRight = stringResource(R.string.desktop_snap_right)
            val restoreSize = stringResource(R.string.desktop_restore_size)
            val close = stringResource(R.string.desktop_close)
            // The one sheet of choices, which a pad drives too (docs/SPEC.md 7k, Droidtop/tracker#690).
            val rows = buildList {
                add(ActionRow(if (minimized) "Restore" else "Minimize", onSelect = { menuOpen = false; onMinimize() }))
                // A title bar's buttons and an edge drag, which sway's windows do not have (Droidtop/tracker#686).
                if (onPlace != null && !minimized) {
                    add(ActionRow(maximize, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.MAXIMIZE) }))
                    add(ActionRow(snapLeft, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.SNAP_LEFT) }))
                    add(ActionRow(snapRight, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.SNAP_RIGHT) }))
                    if (placed) add(ActionRow(restoreSize, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.RESTORE) }))
                }
                add(ActionRow(close, onSelect = { menuOpen = false; onClose() }))
            }
            ActionSheet(title = windowTitle(toplevel), rows = rows, onDismiss = { menuOpen = false })
        }
    }
}

internal fun windowTitle(toplevel: Toplevel): String = toplevel.title.ifBlank { toplevel.appId.ifBlank { "(untitled window)" } }

/**
 * What is pinned to the taskbar (docs/SPEC.md 2b, Droidtop/tracker#348): each pin as its picture, when it has
 * one, and its name, ahead of the window list. A tap starts it, a long press offers Unpin. The pins sit in
 * a row of their own that is capped, so a long list of them cannot take the window list's room.
 */
@Composable
internal fun TaskbarPinChips(pins: List<TaskbarPin>, onOpen: (TaskbarPin) -> Unit, onUnpin: (TaskbarPin) -> Unit) {
    if (pins.isEmpty()) return
    LazyRow(modifier = Modifier.widthIn(max = 260.dp), verticalAlignment = Alignment.CenterVertically) {
        items(pins, key = { it.key }) { pin ->
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .widthIn(max = 140.dp)
                        .heightIn(min = 48.dp)
                        .combinedClickable(onClick = { onOpen(pin) }, onLongClick = { menuOpen = true })
                        .padding(horizontal = 8.dp),
                ) {
                    pin.art?.let { HostedArt(it, size = 24.dp) }
                    Text(
                        pin.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (menuOpen) {
                    val unpin = stringResource(R.string.desktop_unpin)
                    ActionSheet(
                        title = pin.title,
                        rows = listOf(ActionRow(unpin, onSelect = { menuOpen = false; onUnpin(pin) })),
                        onDismiss = { menuOpen = false },
                    )
                }
            }
        }
    }
}

/** An Android app or game in the bar: the app's icon and name; a tap opens it, a long press offers Close. */
@Composable
internal fun TaskbarAppRow(app: RunningApp, onOpen: () -> Unit, onClose: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .widthIn(max = 180.dp)
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp),
        ) {
            AppIcon(app.packageName, size = 20.dp)
            Text(
                app.label,
                color = if (app.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (app.visible) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menuOpen) {
            val close = stringResource(R.string.desktop_close)
            ActionSheet(
                title = app.label,
                rows = listOf(ActionRow(close, onSelect = { menuOpen = false; onClose() })),
                onDismiss = { menuOpen = false },
            )
        }
    }
}

/**
 * Shown only while another keyboard than droidtop's own is active: copying in an Android app
 * then reaches the container only when droidtop's window regains focus (Android lets just the
 * focused app or the active keyboard read the clipboard), so a paste can lag a copy. The menu
 * says why, using the bridge's own wording, and opens the system keyboard switcher; it is the
 * only place the state shows and it never blocks anything (docs/SPEC.md 6a, 6d).
 */
@Composable
internal fun ClipboardNotice() {
    val live by dev.droidtop.hostbridge.ClipboardBridge.androidReadsLive.collectAsState()
    if (live) return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        TaskbarButton(onClick = { open = !open }) {
            Text(stringResource(R.string.desktop_clipboard_on_focus))
        }
        if (open) {
            val title = stringResource(R.string.desktop_clipboard_on_focus)
            val switchKeyboard = stringResource(R.string.desktop_switch_keyboard)
            ActionSheet(
                title = title,
                rows = listOf(
                    ActionNote(dev.droidtop.hostbridge.ClipboardAccess.WHY_BLOCKED),
                    ActionRow(switchKeyboard, onSelect = { open = false; dev.droidtop.library.settings.Keyboards.showPicker(context) }),
                ),
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * The desktop's system tray, over the shared
 * [dev.droidtop.runtime.systemstatus.SystemStatus] core: the readout sits in the bar and the Quick Menu
 * is behind it. Volume, brightness, Do Not Disturb, the network and Bluetooth panels and the notification
 * list are that menu's sections, the same ones Gaming's R2 shows, so the tray keeps no control panel of
 * its own; the readout also counts the notifications waiting (Droidtop/tracker#352) (docs/SPEC.md 2b "Desktop chrome with a pad", Droidtop/tracker#350). Data shared, chrome per
 * surface, the same split the settings catalogs use.
 */
@Composable
internal fun SystemTray(onClick: () -> Unit) {
    val context = LocalContext.current
    val status by remember { dev.droidtop.runtime.systemstatus.SystemStatus.flow(context) }
        .collectAsState(initial = dev.droidtop.runtime.systemstatus.SystemStatus.snapshot(context))
    // What is waiting, so the tray says so without opening the Quick Menu, whose first section it is once
    // notification access is granted. Ongoing notifications (a foreground service's) are not waiting for anyone.
    val waiting = NotificationsStore.items.collectAsState().value.count { it.clearable }
    TaskbarButton(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Gaming's status readout, the one component (Droidtop/tracker#691): the clock in Android's 12 or 24 hour
            // format, the network and battery glyphs and the battery's percent. The desktop's only clock, shown with
            // or without a session and under either compositor.
            StatusCluster(showBatteryPercent = true, onClick = onClick)
            if (status.vpnActive) {
                Text(stringResource(R.string.desktop_tray_vpn), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
            }
            if (waiting > 0) NotificationCount(waiting)
        }
    }
}

/** The notifications waiting, as a count on a badge. */
@Composable
internal fun NotificationCount(count: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
            .padding(horizontal = 5.dp),
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// The container manager in droidtop's screen host (CatalogScreenLink): an
// action, because :shell-desktop has no compile-time dependency on :app.
internal fun openContainers(context: Context) {
    context.startActivity(CatalogScreenLink.intent(context, CONTAINERS_SCREEN_ID))
}

/** `ContainersCatalog.SCREEN_ID` in :app, which this module cannot see. */
internal const val CONTAINERS_SCREEN_ID = "containers"

/** `AppSettingsCatalogs.SCREEN_ROM_FOLDERS` in :app: the game folders, where an empty library is filled. */
internal const val GAME_FOLDERS_SCREEN_ID = "rom_folders"

internal fun openModes(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "dev.droidtop.app.ModeSwitcherActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/**
 * Desktop's settings in droidtop's screen host, the chrome Containers and the places open in (Droidtop/tracker#692),
 * with Desktop's page first and the rest of Settings one row away at its end. It opened Launcher3's settings
 * activity, which drew the same catalog as stock Android preferences; that page stays Standard's.
 */
internal fun openSettings(context: Context) {
    context.startActivity(CatalogScreenLink.intent(context, DESKTOP_SETTINGS_SCREEN_ID))
}

/** `DroidtopWideSettings.SCREEN_DESKTOP` in :app, which this module cannot see. */
internal const val DESKTOP_SETTINGS_SCREEN_ID = "desktop_settings"

/**
 * "Companion" on the taskbar while the device has one screen (docs/SPEC.md "The companion's tabs", One screen): the
 * companion's tabs as a tray panel over the taskbar. With a second screen the companion is there instead.
 */
@Composable
internal fun CompanionTaskbarItem() {
    val context = LocalContext.current
    var offered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        offered = withContext(Dispatchers.IO) { dev.droidtop.runtime.CompanionSheet.offered(context) }
    }
    if (offered) {
        TaskbarButton(onClick = { dev.droidtop.runtime.CompanionSheet.open(context, true) }) { Text(stringResource(R.string.desktop_companion)) }
    }
}

/**
 * "Keyboard" on the bar (docs/SPEC.md 6b "The on-screen keyboard", Droidtop/tracker#685): shows the on-screen keyboard
 * over the desktop, or hides it, and what is typed goes to the program with the focus. Absent while a hardware keyboard
 * is attached, since that keyboard already types into the desktop.
 */
@Composable
internal fun KeyboardTaskbarItem() {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val hardware = configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    if (hardware) return
    TaskbarButton(onClick = { DesktopKeyboardTarget.surface?.get()?.toggleKeyboard() }) {
        Text(stringResource(R.string.desktop_keyboard))
    }
}

/** How often the taskbar checks again whether any plugin has a panel for Desktop; also when the bar is first drawn. */
internal const val PLUGINS_RECHECK_MS = 60_000L

/**
 * "Plugins" on the taskbar (docs/plugin-api.md 1.9): Desktop's way to every plugin's panel, the same list Gaming's
 * Quick Menu shows, drawn in droidtop's plugin window. Absent while no plugin has a panel for Desktop, so the bar shows
 * nothing that does nothing. The check reads manifests and grants only, never a plugin.
 */
@Composable
internal fun PluginTaskbarItems() {
    val context = LocalContext.current
    var hasPanels by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            hasPanels = withContext(Dispatchers.IO) { PluginPanels.panelsFor(context, PluginModes.DESKTOP).isNotEmpty() }
            delay(PLUGINS_RECHECK_MS)
        }
    }
    if (hasPanels) {
        TaskbarButton(
            onClick = {
                PluginHub.open(
                    context,
                    PluginPanels.quickMenuScreen(
                        game = null,
                        showManage = true,
                        onReplyScreen = { screen -> PluginHub.open(context, screen) },
                        surface = PluginModes.Surfaces.DESKTOP_TASKBAR,
                    ),
                )
            },
        ) { Text(stringResource(R.string.desktop_plugins)) }
    }
}

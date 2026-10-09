package dev.droidtop.shell.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.StartLibraryItem
import dev.droidtop.library.StartMenuSections
import dev.droidtop.library.StartSection
import dev.droidtop.library.TaskbarPin
import dev.droidtop.library.TaskbarPins
import dev.droidtop.library.GameNaming
import dev.droidtop.library.integrations.PluginShelves
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.settings.Place
import dev.droidtop.library.settings.SocialBadge
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.shell.gamepad.hosted.HostedArt
import dev.droidtop.shell.gamepad.hosted.HostedCursor
import dev.droidtop.shell.gamepad.hosted.HostedListSheet
import dev.droidtop.shell.gamepad.hosted.HostedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the library holds: the Start menu is the Desktop's one list of it. */
internal val START_MENU_KINDS: Set<LibraryEntryKind> = LibraryEntryKind.entries.toSet()

/** What a Start-menu row stands for: a Linux app of the container or a library entry, and the pin that names it. */
private class StartTarget(val pin: TaskbarPin, val entry: LibraryEntry?, val linux: ContainerApp?)

/**
 * The Start menu (docs/SPEC.md 2b), as one sheet a pad and a finger both drive ([HostedListSheet],
 * Droidtop/tracker#350). Sections, top to bottom: Pinned (what is on the taskbar), droidtop (the places),
 * Linux apps (the container's own desktop entries), a plugin's shelves, then the library's Games (with their
 * artwork), Windows (Wine shortcuts) and Android apps (Droidtop/tracker#348). A takes the row's own action
 * (a PC or engine game takes Gaming's primary-action rule, a store game that is not installed offers the
 * install); X pins it to the taskbar or takes it off; Y and a long press open the row's menu: Open, the
 * game page of a PC or engine game, and pin or unpin.
 */
@Composable
internal fun StartMenu(
    library: Library,
    sessionLive: Boolean,
    linuxApps: List<ContainerApp>,
    linuxAppsError: String?,
    pins: Pins,
    onLaunchLinuxApp: ((ContainerApp) -> Unit)?,
    onPlay: (LibraryEntry) -> Unit,
    onOpenPage: (LibraryEntry) -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // The library as its index holds it (docs/SPEC.md 7g): shown at once
    // from the saved index, walking only what the index does not cover
    // (the app list), in the library's own scope rather than this menu's.
    val entries by library.backgroundScanState(START_MENU_KINDS).collectAsState()
    val play by rememberUpdatedState(onPlay)
    val openPage by rememberUpdatedState(onOpenPage)
    val launchLinux by rememberUpdatedState(onLaunchLinuxApp)
    val dismiss by rememberUpdatedState(onDismiss)
    val search by rememberUpdatedState(onSearch)
    val terminal by rememberUpdatedState(onOpenTerminal)
    var menuFor by remember { mutableStateOf<StartTarget?>(null) }

    LaunchedEffect(library) {
        library.scanInBackground(START_MENU_KINDS)
    }
    // The sections, split and put in name order off the main thread: a library of thousands is one pass.
    val grouped by produceState<Map<StartSection, List<StartLibraryItem>>?>(null, entries) {
        value = entries?.let { list -> withContext(Dispatchers.Default) { StartMenuSections.group(list) } }
    }
    // Plugins' Home shelves, here as Start menu sections of the person's own entries (docs/plugin-api.md 1.9): the same
    // gaming.rows answer Gaming's Home draws, asked for this surface off the main thread and kept 15 minutes.
    var pluginShelves by remember { mutableStateOf<List<PluginShelves.Shelf>>(emptyList()) }
    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        pluginShelves = withContext(Dispatchers.IO) { PluginShelves.shelvesFor(context, list, surface = PluginModes.Surfaces.DESKTOP_START_MENU) }
    }

    // The places (Stores, Social, Downloads and installs, Updates, Plugins): Desktop has no left
    // menu, so the Start menu lists them, from the one place list, each opened in droidtop's
    // screen host (docs/SPEC.md 7j "Places in every mode", Droidtop/tracker#346). Read when the
    // menu opens: the unread count is a plain number the social hub keeps current.
    val places = remember { Place.visible(UiModePrefs.get(context)) }
    val unread = remember { SocialBadge.unread }

    val rows = remember(grouped, entries, linuxApps, linuxAppsError, sessionLive, pluginShelves, places, pins.list, onOpenTerminal != null) {
        val pinned = pins.list.map { it.key }.toSet()
        val byId = entries?.associateBy { it.id }.orEmpty()
        val out = ArrayList<HostedRow>()
        // The one search (docs/SPEC.md 12a), opened from the first row: apps, games and download sources in
        // one ranked list, with the container's apps among them (Droidtop/tracker#351).
        out.add(
            HostedRow(
                key = "search",
                title = "Search",
                subtitle = "Apps, games and download sources",
                onSelect = {
                    dismiss()
                    search("")
                },
            ),
        )

        fun open(target: StartTarget) {
            target.linux?.let { launchLinux?.invoke(it) }
            target.entry?.let { play(it) }
        }

        fun targetOf(entry: LibraryEntry, label: String) =
            StartTarget(TaskbarPin(TaskbarPins.entryKey(entry.id), label, entry.artworkUri), entry, null)

        fun rowFor(
            target: StartTarget,
            key: String,
            section: String,
            subtitle: String? = null,
            art: Boolean = false,
            showPinned: Boolean = true,
        ): HostedRow {
            val picture: @Composable () -> Unit = { HostedArt(target.pin.art) }
            return HostedRow(
                key = key,
                title = target.pin.title,
                section = section,
                subtitle = subtitle,
                value = if (showPinned && target.pin.key in pinned) "Pinned" else null,
                leading = if (art) picture else null,
                onSelect = {
                    open(target)
                    dismiss()
                },
                onToggle = { pins.toggle(target.pin) },
                onDetail = { menuFor = target },
            )
        }

        // What is on the taskbar, first: the things used most. A pin whose app the container no longer
        // has, or whose entry the library no longer holds, stays on the bar and is not listed here.
        pins.list.forEach { pin ->
            val entryId = TaskbarPins.entryIdOf(pin.key)
            val target = if (entryId != null) {
                byId[entryId]?.let { StartTarget(pin, it, null) }
            } else {
                linuxApps.firstOrNull { TaskbarPins.linuxKey(it.id) == pin.key }?.let { StartTarget(pin, null, it) }
            }
            if (target != null) {
                out.add(rowFor(target, key = "pinned:" + pin.key, section = "Pinned", art = pin.art != null, showPinned = false))
            }
        }
        // The taskbar's own buttons, for a pad that has no way to the bar itself.
        terminal?.let { openTerminal ->
            out.add(
                HostedRow(
                    key = "system:terminal",
                    title = "Terminal",
                    section = "System",
                    onSelect = {
                        openTerminal()
                        dismiss()
                    },
                ),
            )
        }
        out.add(HostedRow(key = "system:containers", title = "Containers", section = "System", onSelect = { openContainers(context); dismiss() }))
        out.add(HostedRow(key = "system:modes", title = "Modes", subtitle = "Switch to Gaming, Android or Desktop", section = "System", onSelect = { openModes(context); dismiss() }))
        out.add(HostedRow(key = "system:settings", title = "Settings", section = "System", onSelect = { openSettings(context); dismiss() }))
        places.forEach { place ->
            out.add(
                HostedRow(
                    key = "place:" + place.screenId,
                    title = place.title,
                    section = "droidtop",
                    value = if (place == Place.SOCIAL && unread > 0) "$unread new" else null,
                    onSelect = {
                        context.startActivity(Place.openIntent(context, place))
                        dismiss()
                    },
                ),
            )
        }
        // The primary container's own applications: what the desktop runs. Launched into the session, so
        // their windows appear on the desktop behind this menu.
        if (sessionLive) {
            linuxAppsError?.let { message ->
                out.add(
                    HostedRow(
                        key = "linux-apps-error",
                        title = "Couldn't read the installed apps",
                        subtitle = message,
                        section = "Linux apps",
                        onSelect = {},
                    ),
                )
            }
            linuxApps.forEach { app ->
                val target = StartTarget(TaskbarPin(TaskbarPins.linuxKey(app.id), app.name), null, app)
                // The app's own name, and under it what kind of program it is when the entry says (foot: "Terminal").
                out.add(rowFor(target, key = "linux:" + app.id, section = "Linux apps", subtitle = app.genericName))
            }
        }
        pluginShelves.forEach { shelf ->
            shelf.shelf.entryIds.mapNotNull { byId[it] }.forEach { entry ->
                val target = targetOf(entry, GameNaming.displayName(entry.title))
                out.add(
                    rowFor(
                        target,
                        key = "plugin-shelf:${shelf.pluginId}/${shelf.shelf.id}/" + entry.id,
                        section = "${shelf.shelf.title}, from ${shelf.pluginLabel}",
                        art = true,
                    ),
                )
            }
        }
        val sections = grouped
        if (entries == null || sections == null) {
            out.add(HostedRow(key = "library-loading", title = "Loading the library…", section = "Library", onSelect = {}))
        } else if (sections.isEmpty()) {
            out.add(HostedRow(key = "library-empty", title = "Nothing in the library yet.", section = "Library", onSelect = {}))
        } else {
            StartSection.entries.forEach { section ->
                sections[section].orEmpty().forEach { item ->
                    out.add(rowFor(targetOf(item.entry, item.label), key = "entry:" + item.entry.id, section = section.title, art = true))
                }
            }
        }
        out
    }
    // One window at a time (Droidtop/tracker#371): while a row's menu is open the Start menu is not drawn, and
    // it comes back on the same row.
    val cursor = remember { HostedCursor() }
    val target = menuFor
    if (target == null) {
        HostedListSheet(
            title = "Start",
            rows = rows,
            onClose = onDismiss,
            selectLabel = "Open",
            toggleLabel = "Pin or unpin",
            detailLabel = "Options",
            cursor = cursor,
            // A hardware keyboard typing while the menu is open is the search's: the menu has the focus, and
            // the container gets its keys back when it closes.
            onTyped = { text ->
                dismiss()
                search(text)
            },
        )
    } else {
        // The row's menu: the one place a finger or Y reaches pinning and the game page.
        val pinned = TaskbarPins.isPinned(pins.list, target.pin.key)
        val page = target.entry?.takeIf { it.isPcOrEngineGame }
        val menuRows = ArrayList<HostedRow>()
        menuRows.add(
            HostedRow(
                key = "open",
                title = "Open",
                onSelect = {
                    menuFor = null
                    target.linux?.let { launchLinux?.invoke(it) }
                    target.entry?.let { play(it) }
                    dismiss()
                },
            ),
        )
        if (page != null) {
            menuRows.add(
                HostedRow(
                    key = "page",
                    title = "Game page",
                    subtitle = "Install, runner, saves and more",
                    onSelect = {
                        menuFor = null
                        openPage(page)
                        dismiss()
                    },
                ),
            )
        }
        menuRows.add(
            HostedRow(
                key = "pin",
                title = if (pinned) "Unpin from the taskbar" else "Pin to the taskbar",
                onSelect = {
                    menuFor = null
                    pins.toggle(target.pin)
                },
            ),
        )
        HostedListSheet(
            title = target.pin.title,
            rows = menuRows,
            onClose = { menuFor = null },
            selectLabel = "Choose",
        )
    }
}

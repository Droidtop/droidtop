package dev.droidtop.shell.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.integrations.PluginShelves
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.settings.Place
import dev.droidtop.library.settings.SocialBadge
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.shell.gamepad.hosted.HostedListSheet
import dev.droidtop.shell.gamepad.hosted.HostedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the library holds: the Start menu is the Desktop's one list of it. */
internal val START_MENU_KINDS: Set<LibraryEntryKind> = LibraryEntryKind.entries.toSet()

/**
 * The Start menu (docs/SPEC.md 2b): the places, the container's own applications, plugins' shelves and
 * the library, as one sheet a pad and a finger both drive ([HostedListSheet], Droidtop/tracker#350). A is
 * the entry's own action; Y, and a long press, open a PC or engine game's page.
 */
@Composable
internal fun StartMenu(
    library: Library,
    loadLinuxApps: (suspend () -> List<ContainerApp>)?,
    onLaunchLinuxApp: ((ContainerApp) -> Unit)?,
    onPlay: (LibraryEntry) -> Unit,
    onOpenPage: (LibraryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // The library as its index holds it (docs/SPEC.md 7g): shown at once
    // from the saved index, walking only what the index does not cover
    // (the app list), in the library's own scope rather than this menu's.
    val entries by library.backgroundScanState(START_MENU_KINDS).collectAsState()
    var linuxApps by remember { mutableStateOf<List<ContainerApp>>(emptyList()) }
    var linuxAppsError by remember { mutableStateOf<String?>(null) }
    val play by rememberUpdatedState(onPlay)
    val openPage by rememberUpdatedState(onOpenPage)
    val launchLinux by rememberUpdatedState(onLaunchLinuxApp)
    val dismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(library) {
        library.scanInBackground(START_MENU_KINDS)
    }
    // Plugins' Home shelves, here as Start menu sections of the person's own entries (docs/plugin-api.md 1.9): the same
    // gaming.rows answer Gaming's Home draws, asked for this surface off the main thread and kept 15 minutes.
    var pluginShelves by remember { mutableStateOf<List<PluginShelves.Shelf>>(emptyList()) }
    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        pluginShelves = withContext(Dispatchers.IO) { PluginShelves.shelvesFor(context, list, surface = PluginModes.Surfaces.DESKTOP_START_MENU) }
    }
    // Read again every time the menu opens: what is installed in the
    // container changes whenever the user installs something in it.
    LaunchedEffect(loadLinuxApps) {
        val load = loadLinuxApps ?: return@LaunchedEffect
        try {
            linuxApps = withContext(Dispatchers.IO) { load() }
            linuxAppsError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            linuxAppsError = t.message ?: t.toString()
        }
    }

    // The places (Stores, Social, Downloads and installs, Updates, Plugins): Desktop has no left
    // menu, so the Start menu lists them, from the one place list, each opened in droidtop's
    // screen host (docs/SPEC.md 7j "Places in every mode", Droidtop/tracker#346). Read when the
    // menu opens: the unread count is a plain number the social hub keeps current.
    val places = remember { Place.visible(UiModePrefs.get(context)) }
    val unread = remember { SocialBadge.unread }

    val rows = remember(entries, linuxApps, linuxAppsError, pluginShelves, places) {
        val out = ArrayList<HostedRow>()
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
        // The primary container's own applications first: they are what the desktop runs. Launched
        // into the session, so their windows appear on the desktop behind this menu.
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
            out.add(
                HostedRow(
                    key = "linux:" + app.id,
                    title = app.name,
                    // The app's own name, and under it what kind of program it is when the entry says (foot: "Terminal").
                    subtitle = app.genericName,
                    section = "Linux apps",
                    onSelect = {
                        launchLinux?.invoke(app)
                        dismiss()
                    },
                ),
            )
        }
        val current = entries
        val byId = current?.associateBy { it.id }.orEmpty()
        pluginShelves.forEach { shelf ->
            val shown = shelf.shelf.entryIds.mapNotNull { byId[it] }
            shown.forEach { entry ->
                out.add(
                    HostedRow(
                        key = "plugin-shelf:${shelf.pluginId}/${shelf.shelf.id}/" + entry.id,
                        title = entry.title,
                        section = "${shelf.shelf.title}, from ${shelf.pluginLabel}",
                        onSelect = {
                            play(entry)
                            dismiss()
                        },
                    ),
                )
            }
        }
        when {
            current == null -> out.add(HostedRow(key = "library-loading", title = "Loading…", section = "Library", onSelect = {}))
            current.isEmpty() -> out.add(HostedRow(key = "library-empty", title = "Nothing in the library yet.", section = "Library", onSelect = {}))
            else -> current.forEach { entry ->
                // A tap plays; Y and a long press on a PC or engine game open its page.
                val hasPage = entry.isPcOrEngineGame
                out.add(
                    HostedRow(
                        key = "entry:" + entry.id,
                        title = entry.title,
                        section = "Library",
                        onSelect = {
                            play(entry)
                            dismiss()
                        },
                        onDetail = if (hasPage) {
                            {
                                openPage(entry)
                                dismiss()
                            }
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        out
    }
    HostedListSheet(
        title = "Start",
        rows = rows,
        onClose = onDismiss,
        selectLabel = "Open",
        detailLabel = "Game page",
    )
}

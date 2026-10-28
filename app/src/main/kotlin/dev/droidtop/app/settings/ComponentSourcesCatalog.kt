package dev.droidtop.app.settings

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.runtime.windows.WineBuilds
import dev.droidtop.runtime.windows.WineInstalledComponents
import dev.droidtop.runtime.windows.utils.ComponentCatalog
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where Wine builds, drivers and the other Windows components come from
 * (docs/SPEC.md 5a): add any Wine build by link or file, turn each catalog
 * source on or off, and fetch the catalog again. What a source offers then
 * shows in the Wine build, Driver build, DXVK, VKD3D, FEXCore and Box64 rows.
 * Reached from Wine and graphics.
 */
object ComponentSourcesCatalog {

    /** The link typed into "Link to a Wine build", until it is added. */
    @Volatile private var link: String = ""

    fun screen(): CatalogScreen = CatalogScreen(
        id = "wine_component_sources",
        title = "Wine builds and sources",
        groups = { context ->
            val data = withContext(Dispatchers.IO) { ComponentCatalog.load(context) }
            val fetchedAt = withContext(Dispatchers.IO) { ComponentCatalog.fetchedAt(context) }
            val added = withContext(Dispatchers.IO) { WineBuilds.added(context) }
            val installed = WineInstalledComponents.list(context)
            val add = buildList<CatalogItem> {
                add(
                    TextInputItem(
                        id = "wine_build_link",
                        title = "Link to a Wine build",
                        subtitle = "A .wcp package (Winlator or GameNative format), x86_64 or ARM (arm64ec)",
                        value = link,
                        onChange = { _, text -> link = text.trim() },
                    ),
                )
                if (link.isNotEmpty()) {
                    add(ActionItem(id = "wine_build_link_clear", title = "Clear the link", run = { link = "" }))
                }
                add(
                    AsyncActionItem(
                        id = "wine_build_add_link",
                        title = "Add from the link",
                        run = { ctx, onStatus ->
                            if (link.isEmpty()) "Type the link first" else WineBuilds.addFromUrl(ctx, link, onStatus).also {
                                if (it.startsWith("Added")) link = ""
                            }
                        },
                    ),
                )
                add(
                    DocumentPickItem(
                        id = "wine_build_add_file",
                        title = "Add from a file",
                        subtitle = "A .wcp package on this device",
                        mimeType = "*/*",
                        onPicked = { ctx, uri -> WineBuilds.addFromFile(ctx, uri) },
                    ),
                )
            }
            val sources = data.sources.map { source ->
                if (source.engine == ComponentCatalog.ENGINE_BIONIC) {
                    ToggleItem(
                        id = "component_source_${source.id}",
                        title = source.label,
                        subtitle = source.about,
                        current = ComponentCatalog.isEnabled(context, data, source.id),
                        onToggle = { ctx, on -> withContext(Dispatchers.IO) { ComponentCatalog.setEnabled(ctx, source.id, on) } },
                    )
                } else {
                    ActionItem(
                        id = "component_source_${source.id}",
                        title = source.label,
                        subtitle = listOfNotNull(source.about, "Linux builds need droidtop's Linux engine, which is not available yet")
                            .joinToString(". "),
                        value = "Not available",
                    )
                }
            }
            val refresh = AsyncActionItem(
                id = "component_catalog_refresh",
                title = "Check for new builds",
                value = if (fetchedAt > 0) {
                    "Checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(fetchedAt))
                } else {
                    "Not checked yet"
                },
                run = { ctx, _ ->
                    runCatching { ComponentCatalog.refresh(ctx) }.fold(
                        { "${it.items.values.sumOf { list -> list.size }} components in the catalog" },
                        { "Couldn't check: ${it.message ?: it}" },
                    )
                },
            )
            // What is on the device (Droidtop/tracker#233): each build's size, and Remove for one nothing uses.
            val onDevice = installed.map { c ->
                // A build added by link or file says where from (its WineBuilds id is the store key without the kind).
                val size = android.text.format.Formatter.formatShortFileSize(context, c.bytes) +
                    (added[c.entry.substringAfter('-')]?.let { ". Added from $it" } ?: "")
                if (c.removable) {
                    AsyncActionItem(
                        id = "wine_component_${c.entry}",
                        title = "${c.kind} ${c.name}",
                        subtitle = "$size. No prefix or game uses it",
                        value = "Remove",
                        confirmTitle = "Remove ${c.kind} ${c.name}? A game that picks it later downloads it again if a source offers it",
                        run = { ctx, _ -> WineInstalledComponents.remove(ctx, c.entry) },
                    )
                } else {
                    ActionItem(
                        id = "wine_component_${c.entry}",
                        title = "${c.kind} ${c.name}",
                        subtitle = if (c.bundled) "$size. Comes with droidtop" else "$size. A prefix or a game's own choices use it, so it stays",
                        value = if (c.bundled) "Built in" else "In use",
                    )
                }
            }
            listOfNotNull(
                CatalogGroup(id = "wine_build_add", title = "Add a Wine build", items = add),
                onDevice.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = "wine_components_installed", title = "On this device", items = it) },
                CatalogGroup(id = "component_sources", title = "Sources", items = sources + refresh),
            )
        },
        // Network-backed rows belong to the screen, not to settings search.
        indexGroups = { _ -> emptyList() },
    )
}

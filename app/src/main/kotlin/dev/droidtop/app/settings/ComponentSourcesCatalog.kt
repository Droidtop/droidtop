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
                added.forEach { (id, origin) ->
                    add(ActionItem(id = "wine_build_added_$id", title = id, subtitle = "Added from $origin", value = "Installed", run = {}))
                }
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
                        run = {},
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
            listOf(
                CatalogGroup(id = "wine_build_add", title = "Add a Wine build", items = add),
                CatalogGroup(id = "component_sources", title = "Sources", items = sources + refresh),
            )
        },
        // Network-backed rows belong to the screen, not to settings search.
        indexGroups = { _ -> emptyList() },
    )
}

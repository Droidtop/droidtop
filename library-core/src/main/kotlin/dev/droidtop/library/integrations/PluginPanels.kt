package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.pluginhost.ContextTarget
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginMainUi
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.pluginhost.TileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Plugin panels (docs/plugin-api.md 3 C17, Droidtop/tracker#316): each plugin's control and configuration point,
 * in the Quick Menu the way Decky Loader puts each plugin's panel in Steam's Quick Access Menu. The Quick Menu's
 * Plugins section is a list with one row per plugin; A opens that plugin's panel and B comes back to the list.
 *
 * A panel is drawn by droidtop from data, never by the plugin: its quick and status tiles first (C2, C3), then the
 * view the plugin returns for `ui.panel` `panel` (the view schema of docs/plugin-api.md 1.6, through the one
 * renderer [PluginViews]), then the ways into the plugin's other pages (its settings, its own app, the app it
 * manages). A plugin that declares no `ui.panel` but has tiles on the Quick Menu still gets a panel of its tiles, so
 * every plugin already installed appears here without a change to the plugin.
 *
 * Nothing here runs from list drawing: which plugins have a panel is read from manifests, a panel's view and its
 * tile states are asked for when it opens, and a row's value is the last state already in memory.
 */
object PluginPanels {
    const val POINT = "ui.panel"

    /** Where a panel is drawn, sent as the call's `context.surface`. */
    const val SURFACE_QUICK_MENU = "gaming.quick_menu"
    const val SURFACE_SETTINGS = "settings"

    const val QUICK_MENU_SCREEN_ID = "plugin_panels"

    /**
     * The row that leads from the Quick Menu to the Plugins place (getting plugins, updates, permissions). Its
     * default opens the Plugins screen; the Quick Menu instead closes and opens the place, so the screen has one
     * home and the sheet never holds a second copy of it (docs/SPEC.md "Places").
     */
    const val MANAGE_ROW_ID = "plugin_panels_manage"

    /** One plugin's Quick Menu presence: its `ui.panel` entry if it declared one, and its tiles on the Quick Menu. */
    data class Panel(val record: PluginRecord, val entry: ProvidedPoint?, val tiles: List<PluginTiles.Tile>) {
        val pluginId: String get() = record.manifest.id
        val label: String get() = entry?.label ?: record.manifest.label
    }

    /**
     * Every running plugin with something for the Quick Menu, by name (Decky sorts its list the same way). Reads
     * manifests only; call it off the main thread.
     */
    fun panelsFor(context: Context): List<Panel> {
        val declared = providersOf(context, POINT).distinctBy { it.first.manifest.id }.associateBy { it.first.manifest.id }
        val tiles = PluginTiles.tilesFor(context).groupBy { it.record.manifest.id }
        return (declared.keys + tiles.keys).map { id ->
            val panel = declared[id]
            Panel(record = panel?.first ?: tiles.getValue(id).first().record, entry = panel?.second, tiles = tiles[id].orEmpty())
        }.sortedBy { it.label.lowercase() }
    }

    /** What a list row says about a plugin before it is opened: its first tile's last known state, from memory. */
    fun summary(panel: Panel): String? = panel.tiles.firstNotNullOfOrNull { tile -> PluginTiles.cached(tile)?.let(::tileValue) }

    /**
     * The Quick Menu's Plugins section: one row per plugin that opens its panel, then the way to the Plugins place.
     * [game] is the running game, handed to a panel as `context.game` under the `library.read` rule
     * ([targetArg]). [onReplyScreen] shows a page a quick tile's press answered with, over the sheet.
     */
    fun quickMenuScreen(
        game: ContextTarget?,
        showManage: Boolean,
        onReplyScreen: (CatalogScreen) -> Unit,
    ): CatalogScreen = CatalogScreen(
        id = QUICK_MENU_SCREEN_ID,
        title = "Plugins",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val panels = panelsFor(context)
                // The catalog as last fetched, never a network call: Decky's badge on a plugin with an update.
                val index = PluginCatalog.lastGoodIndex(context)
                val rows: List<CatalogItem> = panels.map { panel ->
                    val update = index?.let { PluginCatalog.updateFor(it, panel.record) } != null
                    val value = if (update) "Update available" else summary(panel)
                    NestedScreenItem(
                        id = "plugin_panel_${panel.pluginId}",
                        title = panel.label,
                        inline = panelScreen(context, panel, SURFACE_QUICK_MENU, game, withMore = true, onReplyScreen = onReplyScreen),
                        valueLabel = { value },
                    )
                }
                listOfNotNull(
                    CatalogGroup(
                        id = "plugin_panels_list",
                        title = null,
                        items = rows.ifEmpty {
                            listOf(ActionItem(id = "plugin_panels_none", title = "No plugin has a panel", subtitle = "Plugins you approve show their panels here", run = {}))
                        },
                    ),
                    if (!showManage) {
                        null
                    } else {
                        CatalogGroup(
                            id = "plugin_panels_more",
                            title = null,
                            items = listOf(
                                NestedScreenItem(
                                    id = MANAGE_ROW_ID,
                                    title = "Get and manage plugins",
                                    subtitle = "The catalog, updates and what each plugin may do",
                                    registryId = AcquireContentSources.PLUGINS_SCREEN_ID,
                                ),
                            ),
                        )
                    },
                )
            }
        },
        // The settings search never opens a plugin's panel: that would call the plugin.
        indexGroups = { emptyList() },
    )

    /**
     * One plugin's panel. [surface] says where it is drawn; [withMore] adds the rows into the plugin's other pages
     * (left out where those rows are already beside it, on the plugin's own page in Settings). Build it off the main
     * thread: the `library.read` check reads the plugin's grants.
     */
    fun panelScreen(
        context: Context,
        panel: Panel,
        surface: String,
        game: ContextTarget?,
        withMore: Boolean,
        onReplyScreen: (CatalogScreen) -> Unit = {},
    ): CatalogScreen {
        val record = panel.record
        val hostContext = JSONObject().put("surface", surface)
        game?.let { hostContext.put("game", targetArg(context, record, it)) }
        val tiles: suspend (Context) -> List<CatalogGroup> = { ctx -> tileGroups(ctx, panel, onReplyScreen) }
        val more: List<CatalogGroup> = if (withMore) moreGroups(record) else emptyList()
        val id = "plugin_panel_${panel.pluginId}_$surface"
        val entry = panel.entry
        return if (entry != null) {
            PluginViews.screen(
                record = record,
                point = POINT,
                op = "panel",
                id = id,
                title = panel.label,
                hostContext = hostContext,
                leadGroups = tiles,
                extraGroups = { _, _ -> more },
            )
        } else {
            CatalogScreen(
                id = id,
                title = panel.label,
                subtitle = "From ${record.manifest.label}",
                groups = { ctx -> withContext(Dispatchers.IO) { tiles(ctx) + more } },
                indexGroups = { emptyList() },
            )
        }
    }

    /** The plugin's tiles as rows: asked for their state when the panel opens (and after each press), never polled. */
    private suspend fun tileGroups(context: Context, panel: Panel, onReplyScreen: (CatalogScreen) -> Unit): List<CatalogGroup> {
        if (panel.tiles.isEmpty()) return emptyList()
        val states = PluginTiles.refresh(context, panel.tiles)
        val items = panel.tiles.map { tile ->
            val state = states[tile.key]
            val title = state?.label ?: tile.fallbackLabel
            if (tile.quick) {
                AsyncActionItem(
                    id = "plugin_tile_${tile.key}",
                    title = title,
                    value = state?.let(::tileValue),
                    run = { ctx, _ ->
                        val outcome = PluginTiles.press(ctx, tile, PluginTiles.cached(tile))
                        outcome?.screen?.let(onReplyScreen)
                        outcome?.message ?: "Done"
                    },
                )
            } else {
                // A status tile only reports: no action, so no A hint (docs/plugin-api.md 1.6, "Pad and hint row").
                ActionItem(id = "plugin_tile_${tile.key}", title = title, value = state?.let(::tileValue), run = {})
            }
        }
        return listOf(CatalogGroup(id = "plugin_panel_${panel.pluginId}_tiles", title = null, items = items))
    }

    /** The ways from a panel into the plugin's other pages: its settings, its own app and the app it manages. */
    private fun moreGroups(record: PluginRecord): List<CatalogGroup> {
        val m = record.manifest
        val items = buildList<CatalogItem> {
            val hasSettings = (m.contractVersion >= 2 && m.v2.provides.any { it.point == PluginSettingsRows.UI_SETTINGS }) ||
                PluginCapability.SETTINGS_ROWS in m.capabilities
            if (hasSettings) {
                add(NestedScreenItem(id = "plugin_panel_${m.id}_settings", title = "Settings", inline = PluginSettingsRows.screenFor(record)))
            }
            if (PluginMainUi.offered(record)) {
                add(
                    AsyncActionItem(
                        id = "plugin_panel_${m.id}_open_main",
                        title = "Open ${m.label}",
                        subtitle = "Its own screen, full-screen. Back returns here",
                        run = { ctx, _ -> PluginMainUi.open(ctx, record) ?: "Opened" },
                    ),
                )
            }
            if (PluginCapability.APP_STATUS in m.capabilities) {
                add(NestedScreenItem(id = "plugin_panel_${m.id}_app_status", title = "App status", inline = PluginAppStatus.screenFor(record)))
            }
        }
        return if (items.isEmpty()) emptyList() else listOf(CatalogGroup(id = "plugin_panel_${m.id}_more", title = null, items = items))
    }

    /** A tile's state as a value column: On or Off for a toggle, else what it reports. */
    internal fun tileValue(state: TileState): String? = when (state.on) {
        true -> "On"
        false -> "Off"
        null -> state.value
    }
}

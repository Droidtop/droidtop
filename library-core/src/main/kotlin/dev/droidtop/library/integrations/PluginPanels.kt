package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.pluginhost.CompanionAbilities
import dev.droidtop.pluginhost.ContextTarget
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginMainUi
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
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
 * plugin's facets for the `quickmenu` target (docs/plugin-api.md 1.10, [PluginFacets]) or, from a plugin that has not
 * moved to facets, the view it returns for `ui.panel` `panel` (the view schema of docs/plugin-api.md 1.6), both
 * through the one renderer [PluginViews], then the ways into the plugin's other pages (its settings, its own app, the
 * app it manages). A plugin that declares no `ui.panel` but has tiles on the Quick Menu still gets a panel of its tiles, so
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
    const val SURFACE_STANDARD = PluginModes.Surfaces.STANDARD_HOME
    const val SURFACE_DESKTOP = PluginModes.Surfaces.DESKTOP_TASKBAR

    /**
     * The companion screen (docs/plugin-api.md 3 C15): the same panels, drawn for touch on its Plugins tab or as a tab
     * of their own, with `context.surface` `<mode>.companion`; panels that declare the `game` ability also give rows for
     * the running game on its Game tab (`<mode>.companion_game`).
     */
    fun surfaceCompanion(mode: String): String = PluginModes.Surfaces.companion(mode)
    fun surfaceCompanionGame(mode: String): String = PluginModes.Surfaces.companionGame(mode)

    /**
     * The panels a person can put on the companion's bar in [mode] (`gaming`), as the tab id the Companion group stores
     * ("plugin:<id>") and the name. Reads manifests: off the main thread.
     */
    fun companionTabs(context: Context, mode: String): List<Pair<String, String>> =
        panelsFor(context, mode).map { dev.droidtop.library.settings.CompanionPrefs.PLUGIN_PREFIX + it.pluginId to it.label }

    const val QUICK_MENU_SCREEN_ID = "plugin_panels"

    /**
     * One plugin's Quick Menu presence: its `ui.panel` entry if it declared one, its tiles on the Quick Menu, and
     * [facets], its `ui.facets` entry when that declares the `quickmenu` target. [companionFacets] is its `ui.facets`
     * entry when that declares `companion.tab` (Droidtop/tracker#602): the companion draws the panel from those facets.
     */
    data class Panel(
        val record: PluginRecord,
        val entry: ProvidedPoint?,
        val tiles: List<PluginTiles.Tile>,
        val facets: ProvidedPoint? = null,
        val companionFacets: ProvidedPoint? = null,
    ) {
        val pluginId: String get() = record.manifest.id
        val label: String get() = entry?.label ?: facets?.label ?: companionFacets?.label ?: record.manifest.label

        /**
         * What the panel declares it does on the companion ([CompanionAbilities]): rows on Game, recording, keep-on. A
         * plugin on facets declares them on its `ui.facets` entry, as a `ui.panel` entry does.
         */
        val abilities: Set<String> get() = CompanionAbilities.of(entry) + CompanionAbilities.of(companionFacets)

        /** Whether the companion's Game tab asks this panel while [runningPackage] runs the game. */
        fun gameRowsFor(runningPackage: String?): Boolean =
            CompanionAbilities.gameRowsFor(entry, runningPackage) || CompanionAbilities.gameRowsFor(companionFacets, runningPackage)
    }

    /**
     * Every running plugin with something for the Quick Menu, by name (Decky sorts its list the same way). Reads
     * manifests only; call it off the main thread.
     */
    fun panelsFor(context: Context, mode: String? = null): List<Panel> {
        val declared = providersOf(context, POINT, mode).distinctBy { it.first.manifest.id }.associateBy { it.first.manifest.id }
        val facetEntries = providersOf(context, PluginFacets.POINT, mode)
        fun facetedFor(target: String) = facetEntries
            .filter { (record, entry) ->
                target in dev.droidtop.pluginhost.FacetGuards.declaredTargets(listOf(entry)) && PluginFacets.shows(context, record, target)
            }
            .distinctBy { it.first.manifest.id }.associateBy { it.first.manifest.id }
        val faceted = facetedFor(PluginFacets.QUICK_MENU)
        val companion = facetedFor(PluginFacets.COMPANION_TAB)
        val tiles = PluginTiles.tilesFor(context, mode = mode).groupBy { it.record.manifest.id }
        return (declared.keys + faceted.keys + companion.keys + tiles.keys).map { id ->
            val panel = declared[id]
            val facets = faceted[id]
            val companionFacets = companion[id]
            Panel(
                record = panel?.first ?: facets?.first ?: companionFacets?.first ?: tiles.getValue(id).first().record,
                entry = panel?.second,
                tiles = tiles[id].orEmpty(),
                facets = facets?.second,
                companionFacets = companionFacets?.second,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** One installed plugin as a list shows it: its panel (or an empty one), how it stands, and its icon. */
    data class Listed(val panel: Panel, val status: PluginStatus.Status, val iconFile: String?)

    /**
     * Every installed plugin, by name, as the Quick Menu's Plugins section and the companion's Plugins tab list them
     * (Droidtop/tracker#644): a plugin with no panel, tiles or facets for [mode] is listed too, with an empty panel
     * whose page is its Settings, Permissions and App status rows. Reads manifests, grants and the catalogs as last
     * fetched (never the network); call it off the main thread.
     */
    fun listed(context: Context, mode: String? = null): List<Listed> {
        val panels = panelsFor(context, mode).associateBy { it.pluginId }
        val listings = PluginCatalog.listings(context)
        val userKeys = dev.droidtop.pluginhost.UserOriginKeys.load(dev.droidtop.pluginhost.UserOriginKeys.storeFile(context))
        val grantStore = dev.droidtop.pluginhost.PluginGrants.forContext(context)
        val waiting = dev.droidtop.pluginhost.PluginApiResolver.current(context).waiting
        val busy = dev.droidtop.pluginhost.PluginJobsCenter.entries().value.filter { !it.done }.mapTo(HashSet()) { it.pluginId }
        return PluginStore.installed(context).map { record ->
            val id = record.manifest.id
            val status = PluginStatus.of(
                record,
                grantStore.read(id),
                dev.droidtop.pluginhost.PluginRuntimeNeeds.missing(context, record.manifest),
                updateAvailable = PluginCatalog.offerFor(listings, record, userKeys) != null,
                waiting = waiting[id],
                working = id in busy,
            )
            Listed(panels[id] ?: Panel(record, entry = null, tiles = emptyList()), status, PluginStore.iconFile(context, record))
        }.sortedBy { it.panel.label.lowercase() }
    }

    /** What a list row says about a plugin before it is opened: its first tile's last known state, from memory. */
    fun summary(panel: Panel): String? = panel.tiles.firstNotNullOfOrNull { tile -> PluginTiles.cached(tile)?.let(::tileValue) }

    /**
     * The Quick Menu's Plugins section, the one home of plugin menus (Droidtop/tracker#644, "Quick Menu > Plugins"):
     * every installed plugin, one row each with its icon, its name and one status glyph ([PluginStatus]), A opening its
     * panel in the sheet (a plugin with no panel of its own still has its Settings, Permissions and App status rows
     * there); then Manage plugins.
     * [game] is the running game, handed to a panel as `context.game` under the `library.read` rule
     * ([targetArg]). [onReplyScreen] shows a page a quick tile's press answered with, over the sheet.
     */
    fun quickMenuScreen(
        game: ContextTarget?,
        showManage: Boolean,
        onReplyScreen: (CatalogScreen) -> Unit,
        surface: String = SURFACE_QUICK_MENU,
    ): CatalogScreen = CatalogScreen(
        // The same list is Standard's and Desktop's way to the panels (docs/plugin-api.md 1.9), named for its surface.
        id = if (surface == SURFACE_QUICK_MENU) QUICK_MENU_SCREEN_ID else "${QUICK_MENU_SCREEN_ID}_$surface",
        title = "Plugins",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val rows: List<CatalogItem> = listed(context, PluginModes.ofSurface(surface)).map { listed ->
                    val panel = listed.panel
                    // The glyph says how it stands; the value is its first tile's state, or the state in words when that
                    // needs the person.
                    val mark = listed.status.mark
                    val value = if (mark != null && mark != dev.droidtop.library.settings.StatusMark.WORKING) listed.status.word else summary(panel)
                    NestedScreenItem(
                        id = "plugin_panel_${panel.pluginId}",
                        title = panel.label,
                        inline = panelScreen(context, panel, surface, game, withMore = true, onReplyScreen = onReplyScreen),
                        valueLabel = { value },
                        iconFile = listed.iconFile,
                        statusMark = mark,
                    )
                }
                listOfNotNull(
                    CatalogGroup(
                        id = "plugin_panels_list",
                        title = null,
                        items = rows.ifEmpty {
                            listOf(ActionItem(id = "plugin_panels_none", title = "No plugins yet", subtitle = "Plugins you add show here, each with its own panel"))
                        },
                    ),
                    if (!showManage) {
                        null
                    } else {
                        CatalogGroup(
                            id = "plugin_panels_more",
                            title = null,
                            items = listOf(
                                // Getting plugins, updates, permissions: the Plugins screen, pushed here in every
                                // surface (Droidtop/tracker#586).
                                NestedScreenItem(
                                    id = "plugin_panels_manage",
                                    title = "Manage plugins",
                                    subtitle = "Get plugins, update them, and say what each may do",
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
        // A bridge plugin whose app is missing gets droidtop's one notice first (#520), never its own rows.
        val tiles: suspend (Context) -> List<CatalogGroup> = { ctx ->
            withContext(Dispatchers.IO) { listOfNotNull(PluginManagedApp.noticeGroup(ctx, record, surface)) } + tileGroups(ctx, panel, onReplyScreen)
        }
        val more: List<CatalogGroup> = if (withMore) moreGroups(context, record) else emptyList()
        val id = "plugin_panel_${panel.pluginId}_$surface"
        val entry = panel.entry
        // The companion draws a plugin's `companion.tab` facets where it has them (Droidtop/tracker#602): about the
        // running game on the Game tab, else about the device.
        val surfaceMode = PluginModes.ofSurface(surface)
        val onGame = surfaceMode != null && surface == PluginModes.Surfaces.companionGame(surfaceMode)
        val onCompanion = onGame || (surfaceMode != null && surface == PluginModes.Surfaces.companion(surfaceMode))
        return if (onCompanion && panel.companionFacets != null) {
            val subject = if (onGame) game?.let { PluginFacets.gameSubject(targetArg(context, record, it)) } else PluginFacets.DEVICE_SUBJECT
            PluginViews.screen(
                record = record,
                point = PluginFacets.POINT,
                op = PluginFacets.OP_GET,
                id = id,
                title = panel.label,
                args = PluginFacets.getArgs(PluginFacets.COMPANION_TAB, subject),
                hostContext = hostContext,
                leadGroups = tiles,
                extraGroups = { _, _ -> more },
                panel = true,
                iconFile = PluginStore.iconFile(context, record),
            )
        } else if (panel.facets != null) {
            // Facets first: a plugin that declares the Quick Menu target is drawn from them, never from its old panel.
            PluginViews.screen(
                record = record,
                point = PluginFacets.POINT,
                op = PluginFacets.OP_GET,
                id = id,
                title = panel.label,
                args = PluginFacets.getArgs(PluginFacets.QUICK_MENU),
                hostContext = hostContext,
                leadGroups = tiles,
                extraGroups = { _, _ -> more },
                panel = true,
                iconFile = PluginStore.iconFile(context, record),
            )
        } else if (entry != null) {
            PluginViews.screen(
                record = record,
                point = POINT,
                op = "panel",
                id = id,
                title = panel.label,
                hostContext = hostContext,
                leadGroups = tiles,
                extraGroups = { _, _ -> more },
                panel = true,
                iconFile = PluginStore.iconFile(context, record),
            )
        } else {
            CatalogScreen(
                id = id,
                title = panel.label,
                subtitle = "From ${record.manifest.label}",
                groups = { ctx -> withContext(Dispatchers.IO) { tiles(ctx) + more } },
                indexGroups = { emptyList() },
                panel = true,
                iconFile = PluginStore.iconFile(context, record),
            )
        }
    }

    /** The plugin's tiles as rows: asked for their state when the panel opens (and after each press), never polled. */
    private suspend fun tileGroups(context: Context, panel: Panel, onReplyScreen: (CatalogScreen) -> Unit): List<CatalogGroup> {
        if (panel.tiles.isEmpty()) return emptyList()
        // A tile that needs the managed app (`"needs": "app"`, #520) is inert with the reason while the app is missing.
        val missing = withContext(Dispatchers.IO) { PluginManagedApp.missing(context, panel.record) }
        val needsApp: (PluginTiles.Tile) -> Boolean = { tile ->
            missing != null && runCatching { JSONObject(tile.entry.extra).optString("needs") }.getOrNull() == PluginManagedApp.NEEDS
        }
        val states = PluginTiles.refresh(context, panel.tiles.filterNot(needsApp))
        val items = panel.tiles.map { tile ->
            if (needsApp(tile)) {
                return@map ActionItem(id = "plugin_tile_${tile.key}", title = tile.fallbackLabel, subtitle = PluginManagedApp.reason(missing!!))
            }
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
                ActionItem(id = "plugin_tile_${tile.key}", title = title, value = state?.let(::tileValue))
            }
        }
        return listOf(CatalogGroup(id = "plugin_panel_${panel.pluginId}_tiles", title = null, items = items))
    }

    /**
     * The ways from a panel into the plugin's other pages: its settings, its own app and the app it manages. First its
     * version, and a newer one from a catalog with the press that installs it (Droidtop/tracker#792: the companion's page
     * showed neither). Reads the catalogs as last fetched, never the network.
     */
    private fun moreGroups(context: Context, record: PluginRecord): List<CatalogGroup> {
        val m = record.manifest
        val userKeys = dev.droidtop.pluginhost.UserOriginKeys.load(dev.droidtop.pluginhost.UserOriginKeys.storeFile(context))
        val offer = PluginCatalog.offerFor(PluginCatalog.listings(context), record, userKeys)
        val items = buildList<CatalogItem> {
            if (offer == null) {
                add(ActionItem(id = "plugin_panel_${m.id}_version", title = "Version", value = m.version))
            } else {
                add(
                    AsyncActionItem(
                        id = "plugin_panel_${m.id}_update",
                        title = "Update to ${offer.release.version}",
                        subtitle = "Installed: ${m.version}",
                        run = { ctx, onStatus -> PluginCatalog.installOutcome(ctx, offer.plugin, offer.release, onStatus).line },
                    ),
                )
            }
            if (PluginSettingsRows.has(record)) {
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
            // Every panel's frame ends with the way to what the plugin may do (Droidtop/tracker#521 item 8): droidtop's own
            // row on every host, so no plugin adds a "permissions" or "about" row of its own.
            add(
                NestedScreenItem(
                    id = "plugin_panel_${m.id}_permissions",
                    title = "Permissions",
                    subtitle = "What ${m.label} may do, and its page",
                    inline = SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID, m.id),
                ),
            )
        }
        return if (items.isEmpty()) emptyList() else listOf(CatalogGroup(id = "plugin_panel_${m.id}_more", title = null, items = items))
    }

    /** A tile's state as a value column: On or Off for a toggle, else what it reports. */
    fun tileValue(state: TileState): String? = when (state.on) {
        true -> "On"
        false -> "Off"
        null -> state.value
    }
}

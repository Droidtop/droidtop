package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Add" screen of the plugin catalog (docs/SPEC.md 12a "The
 * catalog", "The flow"): one row per plugin the index lists -- label,
 * description, and the row's own action (Install / Update to <version> /
 * no action, saying why when there is none) -- with the installed list
 * first on the parent screen, this one only the available side. It is a
 * [CatalogScreen] carried inline by the Plugins screen rather than
 * registered on its own, because it is only reachable from there; both
 * settings renderers already render inline screens.
 *
 * The screen's groups builder may touch the network (the index fetch,
 * [PluginCatalog.currentIndex], off the main thread via
 * [Dispatchers.IO]), and it is deliberate: this is the one surface whose
 * whole job is the catalog, so it is the one place a refresh is
 * automatic; the parent Plugins screen reads the cached copy only.
 */
object PluginCatalogScreen {
    const val ID = "plugins_catalog"

    fun screen(): CatalogScreen = CatalogScreen(
        id = ID,
        title = "Catalog",
        subtitle = "What the plugin catalog lists. Nothing here runs until you approve it on the Plugins screen",
        groups = { context -> catalogGroups(context) },
    )

    private suspend fun catalogGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val load = PluginCatalog.currentIndex(context)
        val installed = PluginStore.installed(context).associateBy { it.manifest.id }
        val items = buildList<CatalogItem> {
            add(
                AsyncActionItem(
                    id = "plugins_catalog_refresh",
                    title = "Refresh catalog",
                    subtitle = "Re-fetch the index from droidtop-platforms",
                    run = { ctx, _ -> PluginCatalog.refresh(ctx) },
                ),
            )
            load.note?.let { note ->
                add(
                    ActionItem(
                        id = "plugins_catalog_note",
                        title = "Catalog status",
                        subtitle = note,
                        run = {},
                    ),
                )
            }
            val index = load.index
            if (index == null) {
                add(
                    ActionItem(
                        id = "plugins_catalog_empty",
                        title = if (load.published) "Nothing to show yet" else "No catalog is published yet",
                        subtitle = if (load.published) {
                            "The refresh above just failed. A plugin file can still be installed from the Plugins screen."
                        } else {
                            "There is nothing to browse yet. Install a plugin file from the Plugins screen instead."
                        },
                        run = {},
                    ),
                )
            } else {
                index.origins.forEach { origin ->
                    origin.plugins.forEach { plugin ->
                        add(rowFor(origin, plugin, installed[plugin.id]))
                    }
                }
            }
        }
        listOf(CatalogGroup(id = "plugins_catalog_list", title = null, items = items))
    }

    private fun rowFor(origin: PluginCatalogOrigin, plugin: PluginCatalogPlugin, installed: PluginRecord?): CatalogItem {
        val subtitle = buildString {
            append(plugin.description ?: "No description")
            append(" - ").append(origin.origin)
            if (PluginCatalog.hasOrderConflict(plugin)) {
                append(" - the catalog lists releases whose versions and dates disagree, so none is offered until it is fixed")
            }
        }
        val latest = PluginCatalog.latestStable(plugin)
        val isUpdate = installed != null && latest != null &&
            !latest.manifestSha256.equals(installed.archiveDigest, ignoreCase = true)
        return when {
            isUpdate -> AsyncActionItem(
                id = "plugins_catalog_${plugin.id}",
                title = plugin.label,
                subtitle = subtitle,
                value = "Update to ${latest.version}",
                run = { ctx, onStatus -> PluginCatalog.install(ctx, plugin, latest!!, onStatus) },
            )
            installed != null -> ActionItem(
                id = "plugins_catalog_${plugin.id}",
                title = plugin.label,
                subtitle = subtitle,
                value = "Installed ${installed.manifest.version}",
                run = {},
            )
            PluginCatalog.originOffered(origin.origin, origin.keySha256) && latest != null -> AsyncActionItem(
                id = "plugins_catalog_${plugin.id}",
                title = plugin.label,
                subtitle = subtitle,
                value = "Install ${latest.version}",
                run = { ctx, onStatus -> PluginCatalog.install(ctx, plugin, latest!!, onStatus) },
            )
            !PluginCatalog.originOffered(origin.origin, origin.keySha256) -> ActionItem(
                id = "plugins_catalog_${plugin.id}",
                title = plugin.label,
                subtitle = subtitle,
                value = "Not available in this build (its origin's key isn't pinned here)",
                run = {},
            )
            else -> ActionItem(
                id = "plugins_catalog_${plugin.id}",
                title = plugin.label,
                subtitle = subtitle,
                value = "No stable release yet",
                run = {},
            )
        }
    }
}

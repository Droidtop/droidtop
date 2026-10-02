package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.app.PcContainerConfigActivity
import dev.droidtop.library.WineSettingsScreen
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.runtime.windows.WineOptionRow
import dev.droidtop.runtime.windows.WineOptions

/**
 * Wine build, x86 emulation, graphics driver and Direct3D as settings rows
 * (docs/SPEC.md 5a): one builder for both places they appear.
 *
 * - Settings > Windows games: the shared environment, which every Windows
 *   game without settings of its own runs in -- the global default.
 * - A game's Wine settings ([WineSettingsScreen], deep-linked from its page
 *   with [WineSettingsScreen.argument]): that game's own prefix once it has one; until then
 *   the shared values, read-only, and the row that gives it its own.
 *
 * Every value is a field of the prefix; [WineOptions] reads and writes it.
 */
object WineOptionsCatalog {

    fun gameScreen(): CatalogScreen = CatalogScreen(
        id = WineSettingsScreen.ID,
        title = "Wine and graphics",
        groups = { context -> groups(context, entryId = null, title = null) },
        // Reached from a game's page only: per-game rows are the screen's,
        // not settings search's (CatalogScreen.indexGroups).
        indexGroups = { _ -> emptyList() },
        forDeepLink = { argument ->
            val (entryId, title) = WineSettingsScreen.parse(argument)
            CatalogScreen(
                id = WineSettingsScreen.ID,
                title = "Wine and graphics",
                subtitle = title,
                groups = { context -> groups(context, entryId, title) },
            )
        },
    )

    /** The rows for [entryId]'s prefix, or the shared environment's when it is null. */
    suspend fun groups(context: Context, entryId: String?, title: String?): List<CatalogGroup> {
        val state = WineOptions.state(context, entryId) ?: return listOf(
            CatalogGroup(
                id = "wine_options_none",
                title = null,
                items = listOf(
                    ActionItem(
                        id = "wine_options_not_set_up",
                        title = "No Windows environment yet",
                        subtitle = "Run Set up Windows games first; these settings belong to the environment it makes",
                        run = {},
                    ),
                ),
            ),
        )
        val shared = entryId == null
        val editable = shared || state.ownPrefix
        val items = buildList<CatalogItem> {
            if (!shared) {
                if (state.ownPrefix) {
                    add(
                        ActionItem(
                            id = "wine_options_own",
                            title = "Settings of its own",
                            subtitle = "This game runs in its own prefix; changes here apply to it alone",
                            value = state.prefixName,
                            run = {},
                        ),
                    )
                } else {
                    add(
                        AsyncActionItem(
                            id = "wine_options_make_own",
                            title = "Use separate settings for this game",
                            subtitle = "Makes a Windows prefix just for this game, starting from the shared settings. " +
                                "A prefix takes a few hundred megabytes; this game's saves made so far stay in the shared one.",
                            value = "Shared",
                            confirmTitle = "Make a prefix just for ${title ?: "this game"}?",
                            run = { ctx, onStatus -> WineOptions.useOwnPrefix(ctx, entryId!!, title ?: entryId, onStatus) },
                        ),
                    )
                }
            }
            state.rows.forEach { row -> add(item(row, entryId, editable)) }
            if (editable && state.missing.isNotEmpty()) {
                add(
                    AsyncActionItem(
                        id = "wine_options_download",
                        title = "Download what these settings need",
                        subtitle = "Not on this device yet: " + state.missing.joinToString(", ") +
                            ". Otherwise it downloads before the next launch.",
                        run = { ctx, onStatus -> WineOptions.download(ctx, entryId, onStatus) },
                    ),
                )
            }
            if (editable) {
                add(
                    ActionItem(
                        id = "wine_options_all",
                        title = "All prefix settings",
                        subtitle = "GameNative's full configuration: controller, drives, environment, components and the rest",
                        run = { ctx -> ctx.startActivity(PcContainerConfigActivity.intent(ctx, entryId, title)) },
                    ),
                )
            }
            add(
                ActionItem(
                    id = "wine_options_sources",
                    title = "Where these come from",
                    subtitle = "Wine, DXVK, VKD3D, FEXCore, Box64 and drivers from GameNative's component list " +
                        "(downloads.gamenative.app and the hosts it names); software Vulkan on x86_64 from Termux's packages, " +
                        "packed by droidtop's gamenative-tux fork",
                    run = {},
                ),
            )
        }
        return listOf(
            CatalogGroup(
                id = if (shared) "wine_options_shared" else "wine_options_game",
                title = if (shared) "Wine and graphics for every game" else null,
                items = items,
            ),
        )
    }

    /** A choice where the row can be changed here; its value, read-only, where it cannot. */
    private fun item(row: WineOptionRow, entryId: String?, editable: Boolean): CatalogItem {
        val label = row.choices.firstOrNull { it.value == row.current }?.label ?: row.current
        if (!editable || row.choices.isEmpty()) {
            return ActionItem(
                id = row.id,
                title = row.title,
                subtitle = if (editable) row.summary else "The shared setting; change it under Settings > Windows games, or give this game its own",
                value = label,
                run = {},
            )
        }
        return ChoiceItem(
            id = row.id,
            title = row.title,
            subtitle = row.summary,
            options = row.choices.map { ChoiceOption(it.value, it.label) },
            current = row.current,
            onSelect = { ctx, value -> WineOptions.select(ctx, entryId, row.id, value) },
        )
    }
}

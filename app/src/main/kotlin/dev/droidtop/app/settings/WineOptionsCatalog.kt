package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.WindowsPrograms
import dev.droidtop.library.WindowsSetup
import dev.droidtop.library.WineSettingsScreen
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.runtime.windows.WineOptionRow
import dev.droidtop.runtime.windows.WineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wine build, x86 emulation, graphics driver and Direct3D as settings rows
 * (docs/SPEC.md 5a): one builder for both places they appear.
 *
 * - Settings > Windows games: the shared environment, which every Windows
 *   game runs in unless it chose another Wine build -- the global default.
 * - A game's Wine settings ([WineSettingsScreen], deep-linked from its page
 *   with [WineSettingsScreen.argument]): the same rows, where a change is
 *   that game's own choice laid over the shared prefix at launch; choosing
 *   another Wine build gives the game a prefix of its own.
 *
 * [WineOptions] decides where each value is kept.
 */
object WineOptionsCatalog {

    fun gameScreen(): CatalogScreen = CatalogScreen(
        id = WineSettingsScreen.ID,
        title = "Wine and graphics",
        groups = { context -> groups(context, entryId = null, title = null, gameRoot = null) },
        // Reached from a game's page only: per-game rows are the screen's,
        // not settings search's (CatalogScreen.indexGroups).
        indexGroups = { _ -> emptyList() },
        forDeepLink = { argument ->
            val target = WineSettingsScreen.parse(argument)
            CatalogScreen(
                id = WineSettingsScreen.ID,
                title = "Wine and graphics",
                subtitle = target.title,
                groups = { context -> groups(context, target.entryId, target.title, target.gameRoot) },
            )
        },
    )

    /** The rows for [entryId] (whose folder is [gameRoot]), or for the shared environment when it is null. */
    suspend fun groups(context: Context, entryId: String?, title: String?, gameRoot: String? = null): List<CatalogGroup> {
        val state = WineOptions.state(context, entryId, gameRoot)
        val shared = entryId == null
        // A game sharing the prefix: its rows are its own choices over it.
        val overGame = !shared && !state.ownPrefix
        val items = buildList<CatalogItem> {
            // Before setup the rows below are the device's defaults and the
            // choices made for setup to use (Droidtop/tracker#372); a game's
            // page has no other way to start it.
            if (!state.setUp && !shared) {
                val setup = withContext(Dispatchers.IO) { WindowsSetup.current(context) }
                add(windowsSetupItem(provisioned = false, setup))
            }
            if (!shared) {
                add(
                    if (state.ownPrefix) {
                        ActionItem(
                            id = "wine_options_where",
                            title = "Runs in its own prefix",
                            subtitle = "This game chose its own Wine build, so it has a Windows prefix of its own; changes here apply to it alone",
                            value = state.prefixName,
                            run = {},
                        )
                    } else {
                        ActionItem(
                            id = "wine_options_where",
                            title = "Runs in the shared prefix",
                            subtitle = "Changes here are this game's own and apply when it starts; the shared settings stay as they are. " +
                                "Choosing another Wine build makes it a prefix of its own.",
                            // A count of this game's own settings over the
                            // shared prefix, not of prefixes: "1 of its own"
                            // under this title read as if a prefix had been
                            // made (rig run, Droidtop/tracker#242).
                            value = when (state.ownChoices) {
                                0 -> "Shared settings"
                                1 -> "Shared, 1 setting changed for this game"
                                else -> "Shared, ${state.ownChoices} settings changed for this game"
                            },
                            run = {},
                        )
                    },
                )
            }
            // Which program the game runs: the one choice screen the
            // game's options, its page and a launch failure open too.
            if (entryId != null && gameRoot != null) {
                add(
                    NestedScreenItem(
                        id = WindowsPrograms.SCREEN_ID,
                        title = "Program",
                        subtitle = "Which program in the game's folder starts it",
                        inline = WindowsPrograms.screen(entryId, title ?: entryId, gameRoot),
                    ),
                )
            }
            state.rows.forEach { row -> add(item(row, entryId, title, overGame)) }
            add(
                NestedScreenItem(
                    id = "wine_component_sources",
                    title = "Wine builds and sources",
                    subtitle = "Add any Wine build by link or file; choose which sources the rows above offer",
                    inline = ComponentSourcesCatalog.screen(),
                ),
            )
            if (overGame && state.ownChoices > 0) {
                add(
                    AsyncActionItem(
                        id = "wine_options_use_shared",
                        title = "Use the shared settings again",
                        subtitle = "Drops this game's own choices; nothing is downloaded or deleted",
                        run = { ctx, _ -> WineOptions.useShared(ctx, entryId!!) },
                    ),
                )
            }
            if (state.missing.isNotEmpty()) {
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
            // Both belong to a prefix that exists.
            if (state.setUp) add(
                NestedScreenItem(
                    id = "wine_options_all",
                    title = if (overGame) "All shared prefix settings" else "All prefix settings",
                    subtitle = if (overGame) "Changes apply to every game that shares the prefix" else null,
                    inline = PrefixSettingsCatalog.screen(if (overGame) null else entryId, title),
                ),
            )
            if (state.setUp) add(
                NestedScreenItem(
                    id = "wine_options_tools",
                    title = "Prefix tools",
                    subtitle = if (overGame) {
                        "Wine configuration, the registry editor and a command prompt, running a program, and stopping Wine, for the shared prefix. They apply to every game that shares it"
                    } else {
                        "Wine configuration, the registry editor and a command prompt, running a program, and stopping Wine, for this prefix"
                    },
                    inline = PrefixToolsCatalog.screen(if (overGame) null else entryId, title, shared = overGame || shared),
                ),
            )
            add(
                ActionItem(
                    id = "wine_options_sources",
                    title = "Where these come from",
                    subtitle = "Wine, DXVK, VKD3D, FEXCore, Box64 and drivers from droidtop's component catalog " +
                        "(github.com/Droidtop/droidtop-components): re-hosted unmodified, or linked where their makers publish them, " +
                        "each download checked against its SHA-256; software Vulkan and the x86_64 libraries built by droidtop's CI",
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

    /** A choice where the build and CPU leave one; the value with its reason where they do not. */
    private fun item(row: WineOptionRow, entryId: String?, title: String?, overGame: Boolean): CatalogItem {
        val label = row.choices.firstOrNull { it.value == row.current }?.label ?: row.current
        val summary = when {
            // Frame generation and Steamworks are always a game's own choice, in any prefix.
            !overGame || row.id == WineOptions.LSFG || row.id == WineOptions.STEAMWORKS || row.id == WineOptions.STEAMWORKS_APPID -> row.summary
            row.id == WineOptions.WINE -> row.summary + ". Another build gives this game a prefix of its own (a few hundred megabytes); its saves so far stay in the shared one."
            row.ownChoice -> row.summary + ". This game's own choice."
            else -> row.summary + ". The shared setting."
        }
        if (row.text) {
            return TextInputItem(
                id = row.id,
                title = row.title,
                subtitle = summary,
                value = row.current,
                onChange = { ctx, text -> WineOptions.select(ctx, entryId, title, row.id, text) },
            )
        }
        if (row.choices.isEmpty()) {
            return ActionItem(id = row.id, title = row.title, subtitle = summary, value = label, run = {})
        }
        return ChoiceItem(
            id = row.id,
            title = row.title,
            subtitle = summary,
            options = row.choices.map { ChoiceOption(it.value, it.label) },
            current = row.current,
            onSelect = { ctx, value -> WineOptions.select(ctx, entryId, title, row.id, value) },
        )
    }
}

/**
 * The one "Set up Windows games" row (or "Reinstall" once it exists): Settings > Library > Windows games and
 * the Wine and graphics page before setup both draw it, with the state in the value column and a question
 * before the long download starts (Droidtop/tracker#299, #367, #372). The same [WindowsSetup.provision] path
 * launch and the game page use.
 */
internal fun windowsSetupItem(provisioned: Boolean, state: WindowsSetup.State): AsyncActionItem = AsyncActionItem(
    id = "windows_provision",
    title = if (provisioned) "Reinstall the Windows environment" else "Set up Windows games",
    subtitle = "Downloads the Wine build, graphics driver and Windows system files, then makes the one Windows environment " +
        "every Windows game runs in. Asks first; uses the game folders you have added.",
    value = WindowsSetup.label(state),
    confirmTitle = if (provisioned) "Reinstall the Windows environment?" else "Download Windows system files?",
    run = { ctx, onStatus ->
        val result = WindowsSetup.provision(ctx, onStatus)
        if (result.succeeded) result.detail else "Failed: ${result.detail}"
    },
)

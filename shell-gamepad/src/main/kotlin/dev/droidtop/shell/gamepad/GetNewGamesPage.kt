package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.integrations.NewGames
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextBlockItem
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Get new games, a page of Updates (docs/SPEC.md 13, "Get new games"; owner, 2026-10-23, Droidtop/tracker#612: "It
 * should open to a recommendations list, with the ability to search at the top"). It opens on a list, never on a
 * blank search bar: Search first, a row like the rest, then what each source features under its name, with its box
 * art, then the person's games a source names a newer version of (each opens Updates, where updates live), then what
 * the sources answer for the games they play most ([NewGames]). A picked game opens the one search on its title, where getting it works as it always does. The
 * Search row shows at once; the rest joins as the sources answer. Drawn by the settings list, so the D-pad, the
 * scrolling and the hint bar are the ones every list has.
 */
@Composable
internal fun GetNewGamesPage(entries: List<LibraryEntry>, onOpenUpdates: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // Null while the sources are asked; the page is drawn again when the answer arrives.
    val loaded = remember { MutableStateFlow<NewGames.Loaded?>(null) }
    LaunchedEffect(entries) { loaded.value = NewGames.load(context, entries) }
    // The search over the sources: null while it is closed; its text, and whether the person chose to type.
    var search by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val screen = remember(entries) {
        CatalogScreen(
            id = "get_new_games",
            title = GetGamesEntry.NEW_GAMES,
            subtitle = "From the sources you have, for the games you play",
            groups = { _ -> newGamesGroups(loaded.value, onSearch = { text, typing -> search = text to typing }, onOpenUpdates) },
            live = loaded,
        )
    }
    CatalogNavigator(root = screen, onExit = onBack)
    search?.let { (text, typing) -> GetGamesSearch(text = text, startTyping = typing) { search = null } }
}

private fun newGamesGroups(
    loaded: NewGames.Loaded?,
    onSearch: (text: String, typing: Boolean) -> Unit,
    onOpenUpdates: () -> Unit,
): List<CatalogGroup> = buildList {
    add(
        CatalogGroup(
            id = "new_games_search",
            title = null,
            items = listOf(
                ActionItem(
                    id = "new_games_search_row",
                    title = "Search for a game",
                    subtitle = "Every source you have, and the sites to search on",
                    icon = CatalogIcon.SEARCH,
                    run = { onSearch("", true) },
                ),
            ),
        ),
    )
    if (loaded == null) {
        add(CatalogGroup(id = "new_games_asking", title = null, items = listOf(TextBlockItem(id = "new_games_asking_text", text = "Asking your sources…"))))
        return@buildList
    }
    // What each source features now (docs/plugin-api.md 1.8 "featured"), under the source's name.
    loaded.featured.forEachIndexed { index, featured ->
        add(
            CatalogGroup(
                id = "new_games_featured_$index",
                title = featured.sourceLabel,
                items = featured.finds.mapIndexed { at, find ->
                    ActionItem(
                        id = "new_games_featured_${index}_$at",
                        title = find.result.title,
                        subtitle = find.result.subtitle?.takeIf { it.isNotBlank() },
                        iconFile = find.result.artUrl,
                        run = { onSearch(find.result.title, false) },
                    )
                },
            ),
        )
    }
    if (loaded.updates.isNotEmpty()) {
        add(
            CatalogGroup(
                id = "new_games_updates",
                title = "New versions of your games",
                items = loaded.updates.map { entry ->
                    ActionItem(
                        id = "new_games_update_${entry.id}",
                        title = entry.title,
                        subtitle = entry.availableUpdate?.let(GameUpdates::line),
                        run = { onOpenUpdates() },
                    )
                },
            ),
        )
    }
    loaded.shelves.forEachIndexed { index, shelf ->
        add(
            CatalogGroup(
                id = "new_games_more_$index",
                title = "More from ${shelf.ask.query}, because you played ${shelf.ask.because}",
                items = shelf.finds.mapIndexed { at, find ->
                    ActionItem(
                        id = "new_games_find_${index}_$at",
                        title = find.result.title,
                        subtitle = listOfNotNull(find.sourceLabel, find.result.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        run = { onSearch(find.result.title, false) },
                    )
                },
            ),
        )
    }
    // Honest when there is nothing: no source at all says how to get one; sources that found nothing say so.
    when {
        loaded.sources == 0 -> add(
            CatalogGroup(
                id = "new_games_no_source",
                title = null,
                items = listOf(
                    TextBlockItem(
                        id = "new_games_no_source_text",
                        text = "No source offers new games yet. A source is a plugin that finds games on a site or in a store.",
                    ),
                    NestedScreenItem(
                        id = "new_games_get_source",
                        title = "Get a game source",
                        subtitle = "Plugins, where sources are added and approved",
                        registryId = AcquireContentSources.PLUGINS_SCREEN_ID,
                    ),
                ),
            ),
        )
        loaded.empty -> add(
            CatalogGroup(
                id = "new_games_nothing",
                title = null,
                items = listOf(
                    TextBlockItem(
                        id = "new_games_nothing_text",
                        text = "Nothing new from your sources for the games you play yet. Search above for any game.",
                    ),
                ),
            ),
        )
    }
}

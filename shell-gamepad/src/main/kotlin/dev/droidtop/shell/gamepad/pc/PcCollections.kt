package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.CollectionMembership
import dev.droidtop.library.CollectionScope
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PcSource
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.menuMove
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.NamedLibraryView

/**
 * One tile of the Collections tab (docs/SPEC.md 7i, "Collections"): a
 * collection or a saved view, with how many games it holds and whether it is
 * a tab. A opens [query] as a grid.
 */
internal data class CollectionTile(
    val key: String,
    val name: String,
    val count: Int,
    val pinned: Boolean,
    val query: LibraryQuery,
    /** Set for a collection, null for a saved view. */
    val collectionId: String? = null,
    /** Set for a saved view: its id. */
    val viewId: String? = null,
)

/** A titled group of tiles: your collections, your saved views, one per store's imported collections. */
internal data class CollectionGroup(val title: String, val tiles: List<CollectionTile>)

/** The query a collection's tile opens and its tab holds: the Collection facet on its id, nothing else. */
internal fun collectionQuery(collectionId: String): LibraryQuery =
    LibraryQuery().withToggled(LibraryFacet.COLLECTION, collectionId, true)

/** The id the tab of a collection pinned from the Collections tab is saved under. */
internal fun collectionViewId(collectionId: String): String = "collection:$collectionId"

/**
 * The Collections tab's groups (docs/SPEC.md 7i): the person's own
 * collections, then their saved views (a pinned collection's own tab is not
 * listed twice), then each store's imported collections under "From <store>".
 * Counts leave hidden games out, a collection whose every game here is hidden
 * is not shown, and an imported one with nothing here is not either. Pure:
 * one pass over [cards] for the counts ([CollectionScope.counts]), one filter
 * pass per saved view.
 */
internal fun collectionGroups(
    cards: List<LibraryEntry>,
    membership: CollectionMembership,
    ids: (LibraryEntry) -> Collection<String>,
    saved: List<NamedLibraryView>,
    scope: LibraryQueryScope,
): List<CollectionGroup> {
    val counts = CollectionScope.counts(cards, membership, ids)
    val hiddenOnly = cards.filter { it.hidden }.flatMapTo(HashSet()) { membership.collectionsOf(ids(it)) } - counts.keys
    val pinnedQueries = saved.filter { it.pinned }.map { it.query }.toSet()
    fun tile(collection: dev.droidtop.library.consoles.CollectionEntity, name: String) = CollectionTile(
        key = "c:${collection.id}",
        name = name,
        count = counts[collection.id] ?: 0,
        pinned = collectionQuery(collection.id) in pinnedQueries,
        query = collectionQuery(collection.id),
        collectionId = collection.id,
    )
    val (imported, own) = membership.collections.filter { it.id !in hiddenOnly }.partition { CollectionScope.isImported(it.id) }
    return buildList {
        own.map { tile(it, it.name) }.takeIf { it.isNotEmpty() }?.let { add(CollectionGroup("Your collections", it)) }
        saved.filterNot { it.id.startsWith("collection:") }
            .map { view ->
                CollectionTile(
                    key = "v:${view.id}",
                    name = view.name,
                    count = cards.count { view.query.matches(it, scope) },
                    pinned = view.pinned,
                    query = view.query,
                    viewId = view.id,
                )
            }
            .takeIf { it.isNotEmpty() }?.let { add(CollectionGroup("Your saved views", it)) }
        imported.groupBy { CollectionScope.importedFrom(it.id).orEmpty() }.forEach { (storeId, collections) ->
            val label = PcSource.Store(storeId).label()
            collections.filter { (counts[it.id] ?: 0) > 0 }
                .map { tile(it, CollectionScope.shortName(it, label)) }
                .takeIf { it.isNotEmpty() }
                ?.let { add(CollectionGroup("From $label", it)) }
        }
    }
}

/** The tiles of [groups] in reading order, the index the cursor moves over. */
internal fun List<CollectionGroup>.tiles(): List<CollectionTile> = flatMap { it.tiles }

/**
 * The visual rows of [groups] at [columns] tiles a row, each group starting a
 * new row under its heading, as tile indices: what Up, Down, Left and Right
 * step through ([tileStep]). Pure.
 */
internal fun tileRows(groups: List<CollectionGroup>, columns: Int): List<List<Int>> {
    var next = 0
    return groups.flatMap { group ->
        group.tiles.indices.map { next++ }.chunked(columns.coerceAtLeast(1))
    }
}

/** Where a press moves the cursor from tile [index]: the same column in the row above or below, or along the row; null at an edge. */
internal fun tileStep(rows: List<List<Int>>, index: Int, action: GamepadAction): Int? {
    val row = rows.indexOfFirst { index in it }.takeIf { it >= 0 } ?: return rows.firstOrNull()?.firstOrNull()
    val column = rows[row].indexOf(index)
    return when (action) {
        GamepadAction.LEFT -> rows[row].getOrNull(column - 1)
        GamepadAction.RIGHT -> rows[row].getOrNull(column + 1)
        GamepadAction.UP -> rows.getOrNull(row - 1)?.let { it[column.coerceAtMost(it.lastIndex)] }
        GamepadAction.DOWN -> rows.getOrNull(row + 1)?.let { it[column.coerceAtMost(it.lastIndex)] }
        else -> null
    }
}

/** The grid index (headings included) of tile [index], for scrolling it into view. */
internal fun gridIndexOfTile(groups: List<CollectionGroup>, index: Int): Int {
    var seen = 0
    groups.forEachIndexed { group, it ->
        if (index < seen + it.tiles.size) return index + group + 1
        seen += it.tiles.size
    }
    return 0
}

/** How many tiles a row of the Collections tab holds at this window's width. */
@Composable
internal fun collectionColumns(): Int {
    val window = LocalShellWindow.current
    val available = window.widthDp - 2 * window.edgePadding.value.toInt()
    return (available / (TILE_WIDTH_DP + 12)).coerceAtLeast(1)
}

private const val TILE_WIDTH_DP = 220

/**
 * The Collections tab (docs/SPEC.md 7i): tiles in groups under their headings,
 * each with its name, its count and, when it is a tab, a mark, without
 * needing focus. The page owns the cursor ([selected]); a tap selects, a
 * second tap opens.
 */
@Composable
internal fun PcCollectionsView(
    groups: List<CollectionGroup>,
    selected: Int?,
    columns: Int,
    state: LazyGridState,
    onTap: (Int) -> Unit,
    empty: String,
) {
    val window = LocalShellWindow.current
    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = window.edgePadding), contentAlignment = Alignment.Center) {
            Text(empty, color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
        }
        return
    }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = window.edgePadding, end = window.edgePadding, top = Space.Sm, bottom = Space.Lg),
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        verticalArrangement = Arrangement.spacedBy(Space.Md),
        modifier = Modifier.fillMaxSize(),
    ) {
        var index = 0
        groups.forEach { group ->
            item(key = "h:${group.title}", span = { GridItemSpan(maxLineSpan) }) {
                Text(group.title, color = MenuTokens.OnSurfaceMuted, style = TypeRole.screenTitle, modifier = Modifier.padding(top = Space.Sm))
            }
            group.tiles.forEach { tile ->
                val at = index++
                item(key = tile.key) { CollectionTileCard(tile, selected == at, onClick = { onTap(at) }) }
            }
        }
    }
}

@Composable
private fun CollectionTileCard(tile: CollectionTile, selected: Boolean, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(dev.droidtop.shell.gamepad.Corners.Crisp)
            .background(if (selected) MenuTokens.SurfaceSelected else MenuTokens.Surface)
            .clickable(onClick = onClick)
            .padding(Space.Md),
    ) {
        Text(tile.name, color = MenuTokens.OnSurface, style = TypeRole.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull("%,d %s".format(tile.count, if (tile.count == 1) "game" else "games"), "Tab".takeIf { tile.pinned }).joinToString(" · "),
            color = MenuTokens.OnSurfaceMuted,
            style = TypeRole.supporting,
            maxLines = 1,
        )
    }
}

/**
 * Select on a tile: pin it as a tab or unpin it, and, for every imported
 * collection at once, Pin all imported and Unpin all imported.
 */
@Composable
internal fun CollectionTileMenu(
    tile: CollectionTile,
    hasImported: Boolean,
    onTogglePin: () -> Unit,
    onPinAllImported: () -> Unit,
    onUnpinAllImported: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = buildList<Pair<String, () -> Unit>> {
        add((if (tile.pinned) "Unpin" else "Pin as a tab") to onTogglePin)
        if (hasImported) {
            add("Pin all imported" to onPinAllImported)
            add("Unpin all imported" to onUnpinAllImported)
        }
        add("Close" to onDismiss)
    }
    ChoiceMenu(title = tile.name, rows = rows, onDismiss = onDismiss)
}

/**
 * The one prompt after the first import that brings a store's collections
 * (docs/SPEC.md 7g, "A store's collections"): choose them in Collections
 * (focused), add them all as tabs, or not now. It says where the tabs go.
 */
@Composable
internal fun ImportedCollectionsPrompt(
    storeLabel: String,
    count: Int,
    onChoose: () -> Unit,
    onAddAll: () -> Unit,
    onNotNow: () -> Unit,
) {
    ChoiceMenu(
        title = "Add your $count $storeLabel collections as tabs after Updates?",
        rows = listOf(
            "Choose in Collections" to onChoose,
            "Add all $count" to onAddAll,
            "Not now" to onNotNow,
        ),
        onDismiss = onNotNow,
    )
}

/** A short panel of choices, the first focused: A chooses, B closes. */
@Composable
private fun ChoiceMenu(title: String, rows: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    var focusIndex by remember { mutableIntStateOf(0) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(480.dp)),
            focusLabel = title,
            title = title,
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, rows.size, press)
                    GamepadAction.A -> rows.getOrNull(focusIndex)?.second?.invoke()
                    GamepadAction.B, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            rows.forEachIndexed { index, (label, action) ->
                MenuRow(title = label, selected = index == focusIndex, onClick = action)
            }
        }
    }
}

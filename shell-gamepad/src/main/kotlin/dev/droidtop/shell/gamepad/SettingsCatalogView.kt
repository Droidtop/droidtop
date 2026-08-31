package dev.droidtop.shell.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.HandheldSettingsCatalog
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.SubScreenItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.shell.gamepad.theme.ThemeBrowserScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handheld's in-shell renderer for the shared HandheldSettingsCatalog
 * (docs/SPEC.md settings architecture): the catalog owns which settings
 * exist, their order and their write paths; this view just chromes them
 * in the shell's own visual language with pure gamepad input, so cycling
 * to Settings with L/R never leaves the handheld context. The unified
 * Preference surface (:shell-default's SettingsHandheldFragment) renders
 * the SAME catalog -- change a value here, see it there, and vice versa.
 *
 * Renderer-native fulfillments (by catalog id, see the catalog's own doc
 * comment): rescan bumps the shell's own scan trigger via [onRescan]
 * instead of relaunching MainActivity, and Browse themes opens
 * [ThemeBrowserScreen] inline instead of deep-linking back into this same
 * shell. Everything else uses the catalog's default behavior --
 * management activities (Console systems, Game folders) and the other
 * settings surfaces are real, deliberate navigations the user explicitly
 * chose, which is exactly the distinction the L/R fix draws: browsing
 * sections never switches context, activating a navigation item does.
 *
 * Input: Up/Down move the selection, Left/Right adjust the selected
 * value in place (choices cycle, sliders step -- real ES-DE's own menu
 * convention), A activates (toggles, cycles a choice forward, runs
 * actions, opens navigations), B backs out to the shell's default
 * section.
 */
@Composable
internal fun SettingsCatalogView(
    onBack: () -> Unit,
    onRescan: () -> Unit,
    browseThemesToken: Int = 0,
) {
    val context = LocalContext.current
    var catalogVersion by remember { mutableStateOf(0) }
    val groups = remember(catalogVersion) { HandheldSettingsCatalog.groups(context) }
    // Flattened for 1:1 selection/scroll mapping; each row remembers the
    // group title it starts, so headers render without breaking indexing.
    val rows = remember(groups) {
        groups.flatMap { group ->
            group.items.mapIndexed { index, item ->
                CatalogRow(item, headerAbove = if (index == 0) group.title else null)
            }
        }
    }
    var selected by remember { mutableStateOf(0) }
    if (selected > rows.lastIndex) selected = rows.lastIndex.coerceAtLeast(0)
    var browseThemes by remember { mutableStateOf(false) }
    // Live outcome text per async item id (e.g. "Theme index updated").
    val asyncStatus = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }

    // Deep link from the unified settings surface's own "Browse themes"
    // entry -- token bumps on every real request (see deepLinkToken's doc
    // comment in GamepadShell).
    LaunchedEffect(browseThemesToken) {
        if (browseThemesToken > 0) browseThemes = true
    }

    if (browseThemes) {
        ThemeBrowserScreen(
            onDismiss = {
                browseThemes = false
                catalogVersion++
            },
        )
        return
    }

    fun refresh() {
        catalogVersion++
    }

    fun adjust(item: CatalogItem, direction: Int) {
        when (item) {
            is ChoiceItem -> {
                if (item.options.isEmpty()) return
                val index = item.options.indexOfFirst { it.value == item.current }
                val next = item.options[(index + direction + item.options.size) % item.options.size]
                item.onSelect(context, next.value)
                refresh()
            }
            is SliderItem -> {
                val next = (item.current + direction).coerceIn(item.min, item.max)
                if (next != item.current) {
                    item.onChange(context, next)
                    refresh()
                }
            }
            is ToggleItem -> {
                item.onToggle(context, !item.current)
                refresh()
            }
            else -> {}
        }
    }

    fun activate(item: CatalogItem) {
        when (item) {
            is ToggleItem -> adjust(item, +1)
            is ChoiceItem -> adjust(item, +1)
            is SliderItem -> {}
            is ActionItem -> when (item.id) {
                HandheldSettingsCatalog.ID_RESCAN_LIBRARY -> {
                    onRescan()
                    asyncStatus[item.id] = "Rescanning..."
                }
                HandheldSettingsCatalog.ID_BROWSE_THEMES -> browseThemes = true
                else -> item.run(context)
            }
            is AsyncActionItem -> {
                asyncStatus[item.id] = "Working..."
                scope.launch {
                    asyncStatus[item.id] = withContext(Dispatchers.IO) { item.run(context) }
                }
            }
            is SubScreenItem -> context.startActivity(item.launchIntent(context))
        }
    }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(listFocus, "Settings catalog") }
    LaunchedEffect(selected) { listState.animateScrollToItem(selected) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(listFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        selected = (selected + 1).coerceAtMost(rows.lastIndex)
                        true
                    }
                    Key.DirectionUp -> {
                        selected = (selected - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionLeft -> {
                        rows.getOrNull(selected)?.let { adjust(it.item, -1) }
                        true
                    }
                    Key.DirectionRight -> {
                        rows.getOrNull(selected)?.let { adjust(it.item, +1) }
                        true
                    }
                    Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                        rows.getOrNull(selected)?.let { activate(it.item) }
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(rows) { index, row ->
            Column {
                row.headerAbove?.let { header ->
                    Text(
                        header,
                        color = Color(0xFF9AA4B2),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp, start = 12.dp),
                    )
                }
                CatalogRowView(
                    row = row,
                    isSelected = index == selected,
                    status = asyncStatus[row.item.id],
                    onClick = {
                        selected = index
                        activate(row.item)
                    },
                )
            }
        }
    }
}

private data class CatalogRow(val item: CatalogItem, val headerAbove: String?)

@Composable
private fun CatalogRowView(
    row: CatalogRow,
    isSelected: Boolean,
    status: String?,
    onClick: () -> Unit,
) {
    val item = row.item
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0x33FFFFFF) else Color.Transparent)
            // Touch still works alongside pure gamepad input -- same
            // reasoning as the rest of the shell's touch support.
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = Color.White,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge,
            )
            val detail = status ?: item.subtitle
            if (detail != null) {
                Text(
                    detail,
                    color = Color(0xFF9AA4B2),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val value = when (item) {
            is ChoiceItem -> item.currentLabel()
            is ToggleItem -> if (item.current) "On" else "Off"
            is SliderItem -> item.current.toString()
            is SubScreenItem -> "›"
            else -> null
        }
        if (value != null) {
            val adjustable = item is ChoiceItem || item is ToggleItem || item is SliderItem
            Text(
                if (isSelected && adjustable) "‹ $value ›" else value,
                color = if (isSelected) Color.White else Color(0xFFB9C2CE),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

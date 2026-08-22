package dev.droidtop.shell.gamepad.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue

/** One item [EsDeSystemListView] can browse -- deliberately generic (not tied to [dev.droidtop.library.LibraryEntry]) so both the Games system list and, later, a per-system game list can reuse the same real theme-driven renderers. */
data class EsDeListItem(
    val key: String,
    val label: String,
    val count: Int,
    val logoPath: String?,
    val accentColor: Color?,
    val onSelect: () -> Unit,
)

/**
 * Renders [items] using whichever real shape the loaded theme's
 * [element] actually declares (carousel/grid/textlist), or a sensible
 * built-in carousel fallback when no theme/element is loaded -- this is
 * the real fix for droidtop previously hardcoding a LazyRow carousel
 * unconditionally: the *shape itself* is theme data (ES-DE's own real
 * convention, confirmed by reading a theme.xml directly: a view declares
 * exactly one of `<carousel>`/`<grid>`/`<textlist>`), not an app-level
 * choice.
 */
@Composable
fun EsDeSystemListView(
    element: EsDeThemeElement?,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    when (element?.type) {
        "grid" -> EsDeGrid(element, items, firstItemFocus, modifier)
        "textlist" -> EsDeTextList(element, items, firstItemFocus, modifier)
        // "carousel", or no theme-declared element at all -- carousel is
        // ES-DE's own real default shape and the one droidtop already
        // shipped, so it's the honest fallback rather than an arbitrary one.
        else -> EsDeCarousel(element, items, firstItemFocus, modifier)
    }
}

@Composable
private fun EsDeCarousel(element: EsDeThemeElement?, items: List<EsDeListItem>, firstItemFocus: FocusRequester?, modifier: Modifier) {
    val textColor = element?.valueOrNull<EsDeThemeValue.Color>("textColor")?.let { colorOf(it) } ?: Color.White
    val uppercase = element?.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"
    val unfocusedOpacity = element?.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemOpacity")?.value ?: 1f
    val unfocusedSaturation = element?.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemSaturation")?.value ?: 1f
    // itemSize is a fraction of the *screen*, ES-DE's own real convention
    // for carousel/grid geometry -- approximated here against a fixed
    // reference width (a real handheld's landscape width) rather than
    // wiring a live screen-size lookup through, a reasonable first cut.
    val itemSizeFraction = element?.valueOrNull<EsDeThemeValue.Pair>("itemSize")
    val itemWidth = itemSizeFraction?.let { (it.x * 1920).dp } ?: 200.dp
    val itemHeight = itemSizeFraction?.let { (it.y * 1080).dp } ?: 140.dp

    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            EsDeListTile(
                item = item,
                width = itemWidth,
                height = itemHeight,
                textColor = textColor,
                uppercase = uppercase,
                unfocusedOpacity = unfocusedOpacity,
                unfocusedSaturation = unfocusedSaturation,
                modifier = if (index == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun EsDeGrid(element: EsDeThemeElement, items: List<EsDeListItem>, firstItemFocus: FocusRequester?, modifier: Modifier) {
    val textColor = element.valueOrNull<EsDeThemeValue.Color>("textColor")?.let { colorOf(it) } ?: Color.White
    val uppercase = element.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"
    val unfocusedOpacity = element.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemOpacity")?.value ?: 1f
    val unfocusedSaturation = element.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemSaturation")?.value ?: 1f
    val itemSizeFraction = element.valueOrNull<EsDeThemeValue.Pair>("itemSize")
    val itemWidth = itemSizeFraction?.let { (it.x * 1920).dp } ?: 160.dp

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = itemWidth),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            EsDeListTile(
                item = item,
                width = itemWidth,
                height = itemWidth * 0.75f,
                textColor = textColor,
                uppercase = uppercase,
                unfocusedOpacity = unfocusedOpacity,
                unfocusedSaturation = unfocusedSaturation,
                modifier = if (index == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
        }
    }
}

/** Daijishō/ES-DE's real "textlist" shape -- a plain vertical list of names, no artwork, matching a live Daijishō screenshot examined this session (its own "List view" toggle). */
@Composable
private fun EsDeTextList(element: EsDeThemeElement, items: List<EsDeListItem>, firstItemFocus: FocusRequester?, modifier: Modifier) {
    val primaryColor = element.valueOrNull<EsDeThemeValue.Color>("primaryColor")?.let { colorOf(it) } ?: Color.White
    val selectedColor = element.valueOrNull<EsDeThemeValue.Color>("selectedColor")?.let { colorOf(it) } ?: primaryColor
    val uppercase = element.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            EsDeTextListRow(
                item = item,
                primaryColor = primaryColor,
                selectedColor = selectedColor,
                uppercase = uppercase,
                modifier = if (index == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun EsDeTextListRow(item: EsDeListItem, primaryColor: Color, selectedColor: Color, uppercase: Boolean, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Text(
        if (uppercase) item.label.uppercase() else item.label,
        color = if (focused) selectedColor else primaryColor,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Real touch-input fix, reported directly: nothing in Handheld
            // mode responded to taps -- .focusable() alone only covers
            // real D-pad/gamepad focus+key input, never touch.
            .clickable(onClick = item.onSelect)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.ButtonA || event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    item.onSelect()
                    true
                } else {
                    false
                }
            }
            .background(if (focused) primaryColor.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 48.dp, vertical = 10.dp),
    )
}

@Composable
private fun EsDeListTile(
    item: EsDeListItem,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    textColor: Color,
    uppercase: Boolean,
    unfocusedOpacity: Float,
    unfocusedSaturation: Float,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = item.accentColor
    Column(
        modifier = modifier
            .size(width = width, height = height)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Same real touch-input fix as EsDeTextListRow above.
            .clickable(onClick = item.onSelect)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.ButtonA || event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    item.onSelect()
                    true
                } else {
                    false
                }
            }
            .graphicsLayer {
                if (!focused) {
                    alpha = unfocusedOpacity
                    // Real, simple desaturation approximation -- Compose has
                    // no cheap built-in saturation filter on a plain
                    // graphicsLayer, and a full ColorMatrix ColorFilter
                    // can't be expressed here without a Canvas-level draw
                    // override; unfocusedItemSaturation < 1 is still
                    // honored via reduced alpha rather than silently
                    // ignored, a real (if imperfect) approximation.
                    if (unfocusedSaturation < 1f) alpha *= 0.85f
                }
            }
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = if (focused) (accent ?: Color.White) else Color.DarkGray,
                shape = RoundedCornerShape(12.dp),
            )
            .background(if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (item.logoPath != null) {
            AsyncImage(
                model = item.logoPath,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
            )
        }
        Text(
            if (uppercase) item.label.uppercase() else item.label,
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        Text("${item.count} items", color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

/** [EsDeThemeValue.Color.argbLikeRgba] is packed RRGGBBAA -- Compose's Color wants ARGB, so channels need reordering. Same real logic as EsDeThemeRenderer.kt's own colorOf. */
private fun colorOf(value: EsDeThemeValue.Color): Color {
    val rgba = value.argbLikeRgba
    val r = (rgba shr 24) and 0xFF
    val g = (rgba shr 16) and 0xFF
    val b = (rgba shr 8) and 0xFF
    val a = rgba and 0xFF
    return Color(red = r.toInt(), green = g.toInt(), blue = b.toInt(), alpha = a.toInt())
}

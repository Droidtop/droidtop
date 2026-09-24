package dev.droidtop.shell.gamepad

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.runtime.systemstatus.NetworkKind
import dev.droidtop.runtime.systemstatus.SystemStatus
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * The Quick Menu's System tab: Android's quick-settings shape (directed
 * 2026-09-10) -- a status header, brightness and volume as sliders, then
 * a grid of large tiles -- over the settings catalog's own System group
 * plus the display-role rows ([QuickTiles.systemGroups]). It replaces a
 * narrow centred list of settings ROWS; what it renders is unchanged,
 * because it is still a view of the catalog and every press goes back to
 * the catalog item's own write path.
 *
 * Controller-first exactly as before: D-pad moves across the strip and
 * the grid as one selection, Left/Right adjusts a focused slider, A acts
 * on a tile (toggles, cycles a small choice, opens a big one or a nested
 * screen, runs an action), B closes. Touch works on every tile too.
 */
@Composable
internal fun QuickSettingsPanel(
    sheetWidthDp: Int,
    onDismiss: () -> Unit,
    tabHint: Pair<GamepadAction, String>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var focusIndex by remember { mutableStateOf(0) }
    var nested by remember { mutableStateOf<CatalogScreen?>(null) }
    var picking by remember { mutableStateOf<ChoiceItem?>(null) }
    var confirmArmedId by remember { mutableStateOf<String?>(null) }
    val statusById = remember { mutableStateMapOf<String, String>() }
    val columns = remember(sheetWidthDp) { QuickTiles.columnsFor(sheetWidthDp) }

    // The live catalog, rebuilt after every change -- a few preference
    // and system reads, off the main thread because SystemStatus takes a
    // battery broadcast and a connectivity query.
    val groups by produceState(initialValue = emptyList<CatalogGroup>(), version) {
        value = withContext(Dispatchers.IO) {
            QuickTiles.systemGroups(GamingSettingsCatalog.groups(context))
        }
    }
    val panel = remember(groups) { QuickTiles.panel(groups) }

    fun refresh() {
        version++
    }

    // A nested catalog screen (software updates, the Android settings
    // index) opens in the sheet through the SAME navigator the Settings
    // section uses, so a screen never renders twice in two ways.
    val nestedScreen = nested
    if (nestedScreen != null) {
        CatalogNavigator(root = nestedScreen, onExit = { nested = null; refresh() })
        return
    }
    val pickingItem = picking
    if (pickingItem != null) {
        CatalogChoicePicker(
            item = pickingItem,
            onPick = { value ->
                pickingItem.onSelect(context, value)
                picking = null
                refresh()
            },
            onDismiss = { picking = null },
        )
        return
    }

    fun activate(item: CatalogItem) {
        when (QuickTiles.pressKind(item)) {
            QuickPress.TOGGLE, QuickPress.CYCLE -> if (adjustCatalogItem(context, item, +1)) refresh()
            QuickPress.PICK -> picking = item as ChoiceItem
            QuickPress.OPEN -> {
                val child = (item as NestedScreenItem).resolve()
                if (child != null) nested = child else statusById[item.id] = "Screen unavailable"
            }
            QuickPress.RUN -> {
                val action = item as? ActionItem ?: return
                if (action.confirmTitle != null && confirmArmedId != action.id) {
                    confirmArmedId = action.id
                    return
                }
                confirmArmedId = null
                action.run(context)
                refresh()
            }
            QuickPress.RUN_ASYNC -> {
                val action = item as AsyncActionItem
                statusById[action.id] = "Working..."
                scope.launch {
                    statusById[action.id] = withContext(Dispatchers.IO) {
                        runCatching { action.run(context) { text -> statusById[action.id] = text } }
                            .getOrElse { "Failed: ${it.message}" }
                    }
                    refresh()
                }
            }
        }
    }

    fun moveTo(direction: QuickMove) {
        confirmArmedId = null
        focusIndex = QuickTiles.move(focusIndex, panel.sliders.size, panel.tiles.size, columns, direction)
    }

    val focus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Quick settings") }
    LaunchedEffect(focusIndex, panel.tiles.size) {
        val tileIndex = focusIndex - panel.sliders.size
        if (tileIndex in panel.tiles.indices) gridState.animateScrollToItem(tileIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val action = GamepadKeyMap.actionFor(event.key)
                val slider = panel.sliders.getOrNull(focusIndex)
                when (action) {
                    GamepadAction.UP -> { moveTo(QuickMove.UP); true }
                    GamepadAction.DOWN -> { moveTo(QuickMove.DOWN); true }
                    GamepadAction.LEFT, GamepadAction.RIGHT -> {
                        val step = if (action == GamepadAction.LEFT) -1 else +1
                        if (slider != null) {
                            // A slider's Left/Right is the adjustment,
                            // not a move (QuickTiles.move says so too).
                            if (adjustCatalogItem(context, slider, step * QuickTiles.sliderStep(slider))) refresh()
                        } else {
                            moveTo(if (step < 0) QuickMove.LEFT else QuickMove.RIGHT)
                        }
                        true
                    }
                    GamepadAction.A -> {
                        if (slider == null) {
                            panel.tiles.getOrNull(focusIndex - panel.sliders.size)?.let { activate(it.item) }
                        }
                        true
                    }
                    GamepadAction.B, GamepadAction.BACK -> { onDismiss(); true }
                    else -> false
                }
            },
    ) {
        QuickStatusHeader()
        panel.sliders.forEachIndexed { index, item ->
            QuickSliderRow(
                item = item,
                focused = focusIndex == index,
                onSet = { value ->
                    focusIndex = index
                    item.onChange(context, value)
                    refresh()
                },
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(panel.tiles) { index, tile ->
                QuickTileView(
                    tile = tile,
                    focused = focusIndex == panel.sliders.size + index,
                    confirmArmed = confirmArmedId == tile.item.id,
                    status = statusById[tile.item.id],
                    onClick = {
                        if (focusIndex != panel.sliders.size + index) confirmArmedId = null
                        focusIndex = panel.sliders.size + index
                        activate(tile.item)
                    },
                )
            }
        }
        // The same bar the Notifications tab beside this one uses: on a
        // touch screen these are the controls, not a legend. L1/R1 is
        // named too: a pad user cannot tap the tabs above.
        TouchHintBar(
            hints = listOf(
                GamepadAction.LEFT to "Lower",
                GamepadAction.RIGHT to "Raise",
                GamepadAction.A to "Act",
                tabHint,
                GamepadAction.B to "Close",
            ),
            background = Color.Transparent,
        )
    }
}

/**
 * The header Android's quick settings shade carries: what time it is,
 * what the battery is doing, what the network is, and -- because this is
 * a handheld whose pad may be the built-in one or a paired controller --
 * which controller is connected.
 */
@Composable
private fun QuickStatusHeader() {
    val context = LocalContext.current
    val initial = remember { SystemStatus.snapshot(context) }
    val flow = remember { SystemStatus.flow(context) }
    val status by flow.collectAsState(initial = initial)
    // A clock has to tick: recomposed every 20 seconds, which is finer
    // than the minute it displays and costs nothing while the sheet is
    // closed (this composition does not exist then).
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(20_000)
        }
    }
    val clock = remember(now) { DateFormat.getTimeFormat(context).format(now) }
    val controller = remember(now) { QuickTiles.describeControllers(connectedControllerNames()) }
    val network = when (status.network) {
        NetworkKind.WIFI -> "Wi-Fi" + (status.wifiLevel?.let { " $it/4" } ?: "")
        NetworkKind.ETHERNET -> "Ethernet"
        NetworkKind.CELLULAR -> "Mobile data"
        NetworkKind.NONE -> "Offline"
    }
    val battery = status.batteryPercent?.let { "$it%" + if (status.charging) " charging" else "" } ?: "Battery unknown"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text(
            clock,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$battery   $network" + if (status.vpnActive) "   VPN" else "",
                color = MenuTokens.Value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                controller ?: "No controller connected",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A quick-settings slider: label, value, and a real fill the D-pad moves. */
@Composable
private fun QuickSliderRow(item: SliderItem, focused: Boolean, onSet: (Int) -> Unit) {
    val span = (item.max - item.min).coerceAtLeast(1)
    val fraction = ((item.current - item.min).toFloat() / span).coerceIn(0f, 1f)
    var rowWidthPx by remember { mutableStateOf(0) }
    fun setFromX(x: Float) {
        if (rowWidthPx <= 0) return
        val f = (x / rowWidthPx).coerceIn(0f, 1f)
        onSet(item.min + kotlin.math.round(f * span).toInt())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(MenuTokens.RowShape)
            .selectionFrame(focused, MenuTokens.RowShape)
            .onSizeChanged { rowWidthPx = it.width }
            // Touch sets the value where you touch, and follows a
            // drag -- what a slider does everywhere else. It used to
            // step up on a tap and WRAP to the minimum at the top,
            // which on a phone means a stray tap at full brightness
            // drops the screen to its dimmest. The D-pad keeps its own
            // stepping; this is the pointer's route to the same write.
            .pointerInput(item.min, item.max, item.current) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> setFromX(offset.x) },
                ) { change, _ -> setFromX(change.position.x) }
            }
            .pointerInput(item.min, item.max) {
                detectTapGestures { offset -> setFromX(offset.x) }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuickGlyphIcon(
                glyph = QuickTiles.glyphFor(item),
                tint = if (focused) MenuTokens.OnSurface else MenuTokens.Value,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                item.title,
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(fraction * 100).toInt()}%",
                color = MenuTokens.Value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MenuTokens.Surface),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (focused) MenuTokens.Accent else MenuTokens.Value),
            )
        }
    }
}

/**
 * One tile. Lit means on (a toggle that is on); dim means off or "this
 * is a value, not a switch" -- the quick-settings cue, kept literal so a
 * glance answers "what is on right now" without reading a word.
 */
@Composable
private fun QuickTileView(
    tile: QuickTile,
    focused: Boolean,
    confirmArmed: Boolean,
    status: String?,
    onClick: () -> Unit,
) {
    val lit = tile.on == true
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        confirmArmed -> MenuTokens.Danger.copy(alpha = 0.22f)
        lit -> MenuTokens.Accent.copy(alpha = 0.24f)
        focused -> MenuTokens.SurfaceSelected
        else -> MenuTokens.Surface
    }
    val tint = when {
        confirmArmed -> MenuTokens.Danger
        lit -> MenuTokens.Accent
        else -> MenuTokens.Value
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(shape)
            .background(background)
            .border(if (focused) MenuTokens.FocusRingWidth else 0.dp, MenuTokens.Accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuickGlyphIcon(glyph = tile.glyph, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            when {
                tile.on != null -> Text(
                    if (tile.on) "On" else "Off",
                    color = if (lit) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                tile.opens -> Text("›", color = MenuTokens.Placeholder, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (confirmArmed) "${tile.label}: press A again" else tile.label,
            color = if (confirmArmed) MenuTokens.Danger else MenuTokens.OnSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val second = status ?: tile.value
        if (second != null) {
            Text(
                second,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The tile glyphs, drawn rather than shipped: droidtop has no icon
 * dependency, and a quick-settings grid without icons is a wall of text.
 * These are plain strokes on a 24-unit square -- small enough to read at
 * tile size, and no new artifact in the repo.
 */
@Composable
internal fun QuickGlyphIcon(glyph: QuickGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun ring(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * u, p(cx, cy), style = stroke)
        fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * u, p(cx, cy))
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) = drawArc(
            color = tint,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = p(cx - r, cy - r),
            size = Size(2 * r * u, 2 * r * u),
            style = stroke,
        )
        when (glyph) {
            QuickGlyph.NETWORK -> {
                arc(12f, 17f, 10f, 200f, 140f)
                arc(12f, 17f, 6.5f, 200f, 140f)
                dot(12f, 17f, 1.7f)
            }
            QuickGlyph.VOLUME -> {
                drawPath(
                    Path().apply {
                        moveTo(4f * u, 9f * u); lineTo(8f * u, 9f * u); lineTo(12f * u, 5f * u)
                        lineTo(12f * u, 19f * u); lineTo(8f * u, 15f * u); lineTo(4f * u, 15f * u); close()
                    },
                    tint,
                )
                arc(12f, 12f, 4.5f, -60f, 120f)
                arc(12f, 12f, 7.5f, -60f, 120f)
            }
            QuickGlyph.BRIGHTNESS -> {
                ring(12f, 12f, 4.5f)
                val rays = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
                rays.forEach { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    val dx = Math.cos(rad).toFloat()
                    val dy = Math.sin(rad).toFloat()
                    line(12f + dx * 7.5f, 12f + dy * 7.5f, 12f + dx * 10f, 12f + dy * 10f)
                }
            }
            QuickGlyph.MOON -> {
                // A crescent: the disc minus a disc offset out of it.
                val full = Path().apply { addOval(Rect(p(3f, 3f), Size(18f * u, 18f * u))) }
                val bite = Path().apply { addOval(Rect(p(9f, 0f), Size(18f * u, 18f * u))) }
                drawPath(Path().apply { op(full, bite, PathOperation.Difference) }, tint)
            }
            QuickGlyph.ROTATE -> {
                arc(12f, 12f, 8f, 30f, 260f)
                drawPath(
                    Path().apply {
                        moveTo(18f * u, 4f * u); lineTo(21f * u, 9f * u); lineTo(15f * u, 9f * u); close()
                    },
                    tint,
                )
            }
            QuickGlyph.TIMER -> {
                ring(12f, 13f, 8f)
                line(12f, 13f, 12f, 8.5f)
                line(12f, 13f, 15.5f, 14.5f)
                line(9f, 2.5f, 15f, 2.5f)
            }
            QuickGlyph.BLUETOOTH -> {
                line(12f, 3f, 12f, 21f)
                line(12f, 3f, 18f, 8.5f); line(18f, 8.5f, 6f, 15.5f)
                line(12f, 21f, 18f, 15.5f); line(18f, 15.5f, 6f, 8.5f)
            }
            QuickGlyph.VPN -> {
                drawRoundRect(
                    tint,
                    topLeft = p(5f, 11f),
                    size = Size(14f * u, 9f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
                arc(12f, 11f, 4f, 180f, 180f)
                dot(12f, 15.5f, 1.4f)
            }
            QuickGlyph.DISPLAY -> {
                drawRoundRect(
                    tint,
                    topLeft = p(3f, 5f),
                    size = Size(18f * u, 11f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
                line(9f, 20f, 15f, 20f)
                line(12f, 16f, 12f, 20f)
            }
            QuickGlyph.GAMEPAD -> {
                drawRoundRect(
                    tint,
                    topLeft = p(2.5f, 7f),
                    size = Size(19f * u, 10f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * u, 5f * u),
                    style = stroke,
                )
                line(6f, 12f, 10f, 12f); line(8f, 10f, 8f, 14f)
                dot(16f, 11f, 1.3f); dot(18.5f, 13.5f, 1.3f)
            }
            QuickGlyph.SWAP -> {
                line(4f, 9f, 18f, 9f); line(14f, 5f, 18f, 9f); line(14f, 13f, 18f, 9f)
                line(20f, 16f, 6f, 16f); line(10f, 12f, 6f, 16f); line(10f, 20f, 6f, 16f)
            }
            QuickGlyph.UPDATE -> {
                arc(12f, 12f, 8f, 120f, 260f)
                line(12f, 6f, 12f, 14f); line(9f, 11f, 12f, 14f); line(15f, 11f, 12f, 14f)
            }
            QuickGlyph.ANDROID -> {
                ring(12f, 12f, 3.2f)
                listOf(0f, 60f, 120f, 180f, 240f, 300f).forEach { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    val dx = Math.cos(rad).toFloat()
                    val dy = Math.sin(rad).toFloat()
                    line(12f + dx * 5.5f, 12f + dy * 5.5f, 12f + dx * 9.5f, 12f + dy * 9.5f)
                }
            }
            QuickGlyph.EXIT -> {
                drawRoundRect(
                    tint,
                    topLeft = p(3f, 4f),
                    size = Size(10f * u, 16f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
                line(10f, 12f, 21f, 12f); line(17f, 8f, 21f, 12f); line(17f, 16f, 21f, 12f)
            }
            QuickGlyph.GENERIC -> {
                ring(12f, 12f, 8f)
                dot(12f, 12f, 2.2f)
            }
        }
    }
}

/**
 * Gamepads Android currently reports. One detector for the whole app:
 * [dev.droidtop.shell.gamepad.input.ControllerPrefs.attachedControllers]
 * answers this here and in onboarding's Controller step.
 */
private fun connectedControllerNames(): List<String> =
    dev.droidtop.shell.gamepad.input.ControllerPrefs.attachedControllers().map { it.name }


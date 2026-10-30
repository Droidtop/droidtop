package dev.droidtop.shell.gamepad

import dev.droidtop.runtime.FailureText
import dev.droidtop.shell.gamepad.input.PressWords
import android.text.format.DateFormat
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.graphics.Color
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
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.onPad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * The Quick Menu's tile sections, System, Audio and Display: Android's
 * quick-settings shape (directed 2026-09-10) -- a status header (System
 * only), sliders, then a grid of large tiles -- over the settings
 * catalog's own System group plus the display-role rows, split between
 * the three by [QuickTiles.sectionGroups]. It replaces a
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
    section: QuickSection,
    sheetWidthDp: Int,
    openPlace: (screenId: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var focusIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
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
            quickSectionGroups(GamingSettingsCatalog.groups(context), section, dev.droidtop.library.settings.UiModePrefs.get(context))
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
                // A screen the left menu lists as a place opens there, not as a copy in this sheet.
                if ((item as NestedScreenItem).registryId?.let(openPlace) == true) return
                val child = item.resolve()
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
                if (action.confirmTitle != null && confirmArmedId != action.id) {
                    confirmArmedId = action.id
                    return
                }
                confirmArmedId = null
                statusById[action.id] = "Working..."
                scope.launch {
                    statusById[action.id] = withContext(Dispatchers.IO) {
                        runCatching { action.run(context) { text -> statusById[action.id] = text } }
                            .getOrElse { "Failed: ${FailureText.of("QuickSettings", it)}" }
                    }
                    refresh()
                }
            }
        }
    }

    // A restored cursor (one saved per section) stays on a slider or tile that still exists.
    LaunchedEffect(panel.sliders.size + panel.tiles.size) {
        val count = panel.sliders.size + panel.tiles.size
        if (focusIndex >= count) focusIndex = (count - 1).coerceAtLeast(0)
    }

    // A held direction follows the cursor without animating each step.
    var heldStep by remember { mutableStateOf(false) }

    fun moveTo(direction: QuickMove, repeat: Boolean) {
        confirmArmedId = null
        heldStep = repeat
        focusIndex = QuickTiles.move(focusIndex, panel.sliders.size, panel.tiles.size, columns, direction)
    }

    val focus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Quick settings") }
    // The selected tile is kept wholly on screen by as little scrolling as
    // it takes (docs/SPEC.md 6e). animateScrollToItem pinned the tile's row
    // to the TOP of the grid on every move, so the grid jumped on each
    // press and restarted the animation at the pad's repeat rate -- the R2
    // menu "can't scroll properly" (tracker#152). Back on the sliders, the
    // grid returns to its first row.
    LaunchedEffect(focusIndex, panel.tiles.size) {
        val tileIndex = focusIndex - panel.sliders.size
        when {
            tileIndex in panel.tiles.indices -> gridState.keepInView(tileIndex, animate = !heldStep)
            panel.tiles.isNotEmpty() -> gridState.keepInView(0, animate = !heldStep)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            // Every press is this tab's: the sheet above has already taken
            // the tabs and the close (docs/SPEC.md 6e). A slider's Left/Right
            // is the adjustment, not a move (QuickTiles.move says so too),
            // and holding it keeps adjusting at the cadence a held direction
            // steps at.
            .onPad { press ->
                val slider = panel.sliders.getOrNull(focusIndex)
                when (press.action) {
                    GamepadAction.UP -> moveTo(QuickMove.UP, press.repeat)
                    GamepadAction.DOWN -> moveTo(QuickMove.DOWN, press.repeat)
                    GamepadAction.LEFT, GamepadAction.RIGHT -> {
                        val step = if (press.action == GamepadAction.LEFT) -1 else +1
                        if (slider != null) {
                            if (adjustCatalogItem(context, slider, step * QuickTiles.sliderStep(slider))) refresh()
                        } else {
                            moveTo(if (step < 0) QuickMove.LEFT else QuickMove.RIGHT, press.repeat)
                        }
                    }
                    GamepadAction.A -> if (slider == null) {
                        panel.tiles.getOrNull(focusIndex - panel.sliders.size)?.let { activate(it.item) }
                    }
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
    ) {
        if (section == QuickSection.SYSTEM) QuickStatusHeader()
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
            itemsIndexed(panel.tiles, key = { _, tile -> tile.item.id }) { index, tile ->
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
        // touch screen these are the controls, not a legend. L1/R1 switches
        // tabs but is no longer named here -- see the ShoulderGlyph beside
        // the tab row itself, above (owner, 2026-09-25: "Can remove the
        // next/previous section pills").
        HintRow(
            // Left/Right adjust only a slider; on a tile they do nothing, so
            // the bar names them only while a slider has the focus.
            bindings = listOf(
                HintBinding(GamepadAction.LEFT, "Lower") { focusIndex < panel.sliders.size },
                HintBinding(GamepadAction.RIGHT, "Raise") { focusIndex < panel.sliders.size },
                HintBinding(GamepadAction.A, "Act"),
                HintBinding(GamepadAction.B, "Close"),
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

/** A quick-settings slider: label, value, and the shell's one slider, which the D-pad moves. */
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
        // The one slider look, Settings' too (ShellSlider).
        ShellSlider(fraction, focused, Modifier.fillMaxWidth().padding(top = 8.dp))
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
    val shape = Corners.Plate
    // The selection is drawn only while a pad drives (docs/SPEC.md 6e).
    val ring = focused && dev.droidtop.shell.gamepad.input.PadModality.showsFocus
    val background = when {
        confirmArmed -> MenuTokens.NoticeDanger.copy(alpha = 0.22f)
        lit -> MenuTokens.Accent.copy(alpha = 0.24f)
        ring -> MenuTokens.SurfaceSelected
        else -> MenuTokens.Surface
    }
    val tint = when {
        confirmArmed -> MenuTokens.NoticeDanger
        lit -> MenuTokens.Accent
        else -> MenuTokens.Value
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .clip(shape)
            .background(background)
            .focusRing(ring, shape)
            .padRow(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuickGlyphIcon(glyph = tile.glyph, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            when {
                // The one switch look, Settings' too (ShellSwitch); a flip is one A, as before.
                tile.on != null -> ShellSwitch(tile.on)
                tile.opens -> Text("›", color = MenuTokens.Placeholder, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (confirmArmed) "${tile.label}: ${PressWords.again}" else tile.label,
            color = if (confirmArmed) MenuTokens.NoticeDanger else MenuTokens.OnSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = if (focused) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.focusMarquee(focused),
        )
        val second = status ?: tile.value
        if (second != null) {
            Text(
                second,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (focused) 1 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.focusMarquee(focused),
            )
        }
    }
}

/**
 * The catalog groups one tile section (System, Audio or Display) shows: the one place that list is made, drawn by
 * the Quick Menu here and by the companion's System tab (docs/SPEC.md "The companion's tabs"), so the two never
 * differ. The System section also carries the two controller tiles (SPEC 7b), which live in this module because
 * they act on the shell's own controller resolver. [catalog] is the live Gaming catalog
 * ([GamingSettingsCatalog.groups], built off the main thread).
 */
fun quickSectionGroups(
    catalog: List<CatalogGroup>,
    section: QuickSection,
    mode: dev.droidtop.library.settings.UiMode,
): List<CatalogGroup> {
    val base = QuickTiles.sectionGroups(catalog, section, mode)
    return if (section != QuickSection.SYSTEM) base else base.map { it.copy(items = it.items + ControllerLayoutTiles.items()) }
}

/**
 * Gamepads Android currently reports. One detector for the whole app:
 * [dev.droidtop.shell.gamepad.input.ControllerLayouts.attachedControllers]
 * answers this here and in onboarding's Controller step.
 */
private fun connectedControllerNames(): List<String> =
    dev.droidtop.shell.gamepad.input.ControllerLayouts.attachedControllers().map { it.name }


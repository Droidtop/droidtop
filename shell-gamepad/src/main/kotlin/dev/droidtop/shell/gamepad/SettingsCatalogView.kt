package dev.droidtop.shell.gamepad

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.HandheldSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.SubScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.shell.gamepad.theme.ThemeBrowserScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-shell (gamepad-first, dark-chrome) renderer for the shared
 * settings catalogs (docs/SPEC.md settings architecture): catalogs own
 * which settings exist, their layout and their write paths; this
 * navigator just chromes them -- including nested management screens
 * ([CatalogScreen]: console systems, platform CRUD, ROM folders, scraper
 * credentials), which push onto a real nav stack in the same visual
 * language instead of bouncing to differently-styled activities.
 *
 * Input: Up/Down move the selection, Left/Right adjust the selected
 * value in place (choices cycle, sliders step -- real ES-DE's own menu
 * convention), A activates (toggles, opens pickers/nested screens/text
 * editors, runs actions), B pops one level and exits at the root.
 * Touch works on every row too.
 *
 * [nativeActions]: renderer-native fulfillments by catalog item id (see
 * the catalog doc comment) -- when present, activating that item calls
 * the override instead of the item's own default run.
 */
@Composable
fun CatalogNavigator(
    root: CatalogScreen,
    onExit: () -> Unit,
    nativeActions: Map<String, () -> Unit> = emptyMap(),
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf(root) }
    // Selection is per-depth so popping restores where the user was.
    val selectionByDepth = remember { mutableStateMapOf<Int, Int>() }
    // Live status text per item id (async progress/outcomes, pick errors).
    val statusById = remember { mutableStateMapOf<String, String>() }
    // Two-step confirm: the armed destructive item, reset on any move.
    var confirmArmedId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<TextInputItem?>(null) }
    var pickingChoice by remember { mutableStateOf<ChoiceItem?>(null) }
    var pendingFolderPick by remember { mutableStateOf<FolderPickItem?>(null) }
    val scope = rememberCoroutineScope()

    val screen = stack.last()
    val depth = stack.lastIndex
    // Suspend builder (real screens run Room queries / filesystem walks) --
    // rebuilt on every navigation and after every value change.
    val groups by androidx.compose.runtime.produceState(initialValue = emptyList<CatalogGroup>(), screen, version) {
        value = screen.groups(context)
    }
    val rows = remember(groups) {
        groups.flatMap { group ->
            group.items.mapIndexed { index, item ->
                CatalogRow(item, headerAbove = if (index == 0) group.title else null)
            }
        }
    }
    val selected = (selectionByDepth[depth] ?: 0).coerceIn(0, (rows.lastIndex).coerceAtLeast(0))

    fun refresh() {
        version++
    }

    fun setSelected(index: Int) {
        selectionByDepth[depth] = index
        confirmArmedId = null
    }

    val folderPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val item = pendingFolderPick
        pendingFolderPick = null
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val error = item.onPicked(context, uri)
        if (error != null) statusById[item.id] = error else statusById.remove(item.id)
        refresh()
    }

    fun pop() {
        when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                refresh()
            }
            else -> onExit()
        }
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
        val native = nativeActions[item.id]
        if (native != null) {
            native()
            return
        }
        when (item) {
            is ToggleItem -> adjust(item, +1)
            // Small sets cycle in place; big ones (a 100-system picker)
            // get a real selection list.
            is ChoiceItem -> if (item.options.size <= 6) adjust(item, +1) else pickingChoice = item
            is SliderItem -> {}
            is TextInputItem -> editingText = item
            is FolderPickItem -> {
                pendingFolderPick = item
                folderPickLauncher.launch(null)
            }
            is ActionItem -> {
                if (item.confirmTitle != null && confirmArmedId != item.id) {
                    confirmArmedId = item.id
                    return
                }
                confirmArmedId = null
                item.run(context)
                refresh()
            }
            is AsyncActionItem -> {
                statusById[item.id] = "Working..."
                scope.launch {
                    statusById[item.id] = withContext(Dispatchers.IO) {
                        runCatching { item.run(context) { status -> statusById[item.id] = status } }
                            .getOrElse { "Failed: ${it.message}" }
                    }
                    refresh()
                }
            }
            is NestedScreenItem -> {
                val child = item.resolve()
                if (child != null) {
                    stack.add(child)
                    selectionByDepth[stack.lastIndex] = 0
                    refresh()
                } else {
                    statusById[item.id] = "Screen unavailable"
                }
            }
            is SubScreenItem -> context.startActivity(item.launchIntent(context))
        }
    }

    BackHandler { pop() }

    // Modal overlays render INSTEAD of the list so their own input wins.
    val textItem = editingText
    if (textItem != null) {
        TextEditDialog(
            item = textItem,
            onCommit = { newValue ->
                textItem.onChange(context, newValue)
                editingText = null
                refresh()
            },
            onDismiss = { editingText = null },
        )
    }
    val choiceItem = pickingChoice
    if (choiceItem != null) {
        ChoicePickerScreen(
            item = choiceItem,
            onPick = { value ->
                choiceItem.onSelect(context, value)
                pickingChoice = null
                refresh()
            },
            onDismiss = { pickingChoice = null },
        )
        return
    }

    val listState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    LaunchedEffect(screen, textItem == null) { if (textItem == null) requestFocusWhenAttached(listFocus, "Settings catalog") }
    LaunchedEffect(selected, screen) { if (rows.isNotEmpty()) listState.animateScrollToItem(selected.coerceIn(0, rows.lastIndex)) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (stack.size > 1 || screen.subtitle != null) {
            Column(Modifier.padding(horizontal = 48.dp).padding(top = 18.dp, bottom = 2.dp)) {
                Text(screen.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                screen.subtitle?.let {
                    Text(
                        it,
                        color = Color(0xFF8A93A1),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
                            setSelected((selected + 1).coerceAtMost(rows.lastIndex))
                            true
                        }
                        Key.DirectionUp -> {
                            setSelected((selected - 1).coerceAtLeast(0))
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
                .padding(horizontal = 48.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(rows) { index, row ->
                Column {
                    row.headerAbove?.let { header ->
                        Text(
                            header.uppercase(),
                            color = Color(0xFF7D8794),
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
                            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
                        )
                    }
                    CatalogRowView(
                        row = row,
                        isSelected = index == selected,
                        confirmArmed = confirmArmedId == row.item.id,
                        status = statusById[row.item.id],
                        onClick = {
                            setSelected(index)
                            activate(row.item)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Handheld's Settings section: the handheld settings catalog rendered by
 * [CatalogNavigator], with the shell's renderer-native fulfillments
 * (rescan bumps the scan trigger via [onRescan]; Browse themes opens
 * [ThemeBrowserScreen] inline, also reachable by deep link via
 * [browseThemesToken]).
 */
@Composable
internal fun SettingsCatalogView(
    onBack: () -> Unit,
    onRescan: () -> Unit,
    browseThemesToken: Int = 0,
) {
    var browseThemes by remember { mutableStateOf(false) }

    LaunchedEffect(browseThemesToken) {
        if (browseThemesToken > 0) browseThemes = true
    }

    if (browseThemes) {
        ThemeBrowserScreen(onDismiss = { browseThemes = false })
        return
    }

    val root = remember {
        CatalogScreen(
            id = "handheld_settings",
            title = "Settings",
            groups = { ctx -> HandheldSettingsCatalog.groups(ctx) },
        )
    }
    CatalogNavigator(
        root = root,
        onExit = onBack,
        nativeActions = mapOf(
            HandheldSettingsCatalog.ID_RESCAN_LIBRARY to onRescan,
            HandheldSettingsCatalog.ID_BROWSE_THEMES to { browseThemes = true },
        ),
    )
}

private data class CatalogRow(val item: CatalogItem, val headerAbove: String?)

@Composable
private fun CatalogRowView(
    row: CatalogRow,
    isSelected: Boolean,
    confirmArmed: Boolean,
    status: String?,
    onClick: () -> Unit,
) {
    val item = row.item
    val accent = (item as? NestedScreenItem)?.accent?.let { Color(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0x2BFFFFFF) else Color(0x0DFFFFFF))
            // Touch still works alongside pure gamepad input -- same
            // reasoning as the rest of the shell's touch support.
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (confirmArmed) "${item.title} -- press A again to confirm" else item.title,
                color = if (confirmArmed) Color(0xFFFFB4AB) else Color.White,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val detail = status ?: item.subtitle
            if (detail != null) {
                Text(
                    detail,
                    color = Color(0xFF8A93A1),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        val context = LocalContext.current
        // Value and navigation are DIFFERENT trailing affordances: a
        // chevron never dresses up as a value again, and "(not set)"
        // stops shouting in body-large.
        val chevron = item is NestedScreenItem || item is SubScreenItem
        val value = when (item) {
            is ChoiceItem -> item.currentLabel()
            is ToggleItem -> if (item.current) "On" else "Off"
            is SliderItem -> item.current.toString()
            is TextInputItem -> if (item.secret && item.value.isNotEmpty()) "••••" else item.value.ifEmpty { null }
            is NestedScreenItem -> item.valueLabel?.invoke(context)
            else -> null
        }
        val placeholder = value == null && item is TextInputItem
        if (value != null || placeholder) {
            val adjustable = (item is ChoiceItem && item.options.size <= 6) || item is ToggleItem || item is SliderItem
            Text(
                if (isSelected && adjustable) "‹ ${value ?: ""} ›" else (value ?: "not set"),
                color = when {
                    placeholder -> Color(0xFF6B7480)
                    isSelected -> Color.White
                    else -> Color(0xFFAEB7C4)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = Color(0xFF6B7480), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Full-screen option list for large [ChoiceItem]s (system pickers etc.). */
@Composable
private fun ChoicePickerScreen(
    item: ChoiceItem,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(item.options.indexOfFirst { it.value == item.current }.coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Choice picker") }
    LaunchedEffect(selected) { if (item.options.isNotEmpty()) listState.animateScrollToItem(selected) }
    BackHandler { onDismiss() }

    Column(Modifier.fillMaxSize()) {
        Text(
            item.title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 48.dp).padding(top = 20.dp, bottom = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selected = (selected + 1).coerceAtMost(item.options.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            true
                        }
                        Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            item.options.getOrNull(selected)?.let { onPick(it.value) }
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 48.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(item.options) { index, option ->
                Text(
                    option.label,
                    color = if (index == selected) Color.White else Color(0xFFAEB7C4),
                    fontWeight = if (index == selected) FontWeight.Medium else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (index == selected) Color(0x2BFFFFFF) else Color(0x0DFFFFFF))
                        .clickable { onPick(option.value) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun TextEditDialog(
    item: TextInputItem,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(item) { mutableStateOf(item.value) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1A1A))
                .padding(20.dp),
        ) {
            Text(item.title, color = Color(0xFFEDEDED), style = MaterialTheme.typography.titleMedium)
            item.subtitle?.let {
                Text(it, color = Color(0xFF9AA4B2), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = !item.multiline,
                visualTransformation = if (item.secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFEDEDED)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A2A))
                    .padding(12.dp),
            )
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { value = it }
                }) { Text("Paste", color = Color(0xFF8AB4FF)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF9AA4B2)) }
                TextButton(onClick = { onCommit(value) }) { Text("Save", color = Color(0xFF8AB4FF)) }
            }
        }
    }
}

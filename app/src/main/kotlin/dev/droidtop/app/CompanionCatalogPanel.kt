package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.settings.confirmText
import dev.droidtop.shell.gamepad.QuickSection
import dev.droidtop.shell.gamepad.QuickTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.compose.AsyncImage
import dev.droidtop.ui.shared.LocalNoticePalette
import dev.droidtop.ui.shared.NoticeCard

/**
 * One card of the companion's System tab (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): a titled
 * list of catalog items, folded or open. [storage] marks the one card that is not catalog items (free space).
 */
internal data class SystemCard(
    val id: String,
    val title: String,
    val items: List<CatalogItem>,
    val folded: Boolean,
    val storage: Boolean = false,
)

/**
 * The System tab's cards, in order: the Quick Menu's own System section open at the top, then folded Display,
 * Sound, Power, Storage and Privacy. [section] is the Quick Menu's item list for a section (`quickSectionGroups`),
 * so the two surfaces show the same items by construction; performance mode and the privacy dashboard are the
 * catalog's own items too. A catalog card with nothing in it is left out. Pure.
 */
internal fun companionSystemCards(
    section: (QuickSection) -> List<CatalogItem>,
    performanceMode: CatalogItem?,
    privacy: CatalogItem?,
    accessibility: List<CatalogItem> = emptyList(),
): List<SystemCard> = listOf(
    SystemCard("system", "System", section(QuickSection.SYSTEM), folded = false),
    SystemCard("display", "Display", section(QuickSection.DISPLAY), folded = true),
    SystemCard("sound", "Sound", section(QuickSection.AUDIO), folded = true),
    // Network detail and Bluetooth devices, drawn by the card itself (slice C19).
    SystemCard("connections", "Connections", section(QuickSection.CONNECTIONS), folded = true),
    SystemCard("power", "Power", listOfNotNull(performanceMode), folded = true),
    SystemCard("storage", "Storage", emptyList(), folded = true, storage = true),
    SystemCard("privacy", "Privacy", listOfNotNull(privacy), folded = true),
    // Android's accessibility settings (slice C22): written with the helper app, else opening Android's screens.
    SystemCard("accessibility", "Accessibility", accessibility, folded = true),
).filter { it.storage || it.id in DRAWN_CARDS || it.items.isNotEmpty() }

/** Cards that draw rows of their own beside any catalog items, so they show even with none. */
private val DRAWN_CARDS = setOf("display", "sound", "connections", "power", "privacy")

/**
 * Catalog items drawn for touch: the companion's half of "the Quick Menu and the companion draw the same control
 * models". Every row is at least 56dp and says what it does under its name; a slider has a step button on each
 * side; a row that asks first shows the safe answer first. Every press goes back to the item's own write path and
 * then [onChanged] re-reads the catalog; nothing here holds a value of its own.
 */
@Composable
internal fun CompanionCatalogItems(
    items: List<CatalogItem>,
    onChanged: () -> Unit,
    onOpenSocial: (() -> Unit)? = null,
    asks: (CatalogItem) -> Boolean = { it.confirmText != null },
) {
    var nested by remember { mutableStateOf<CatalogScreen?>(null) }
    val open = nested
    if (open != null) {
        CompanionNestedScreen(open, onBack = { nested = null }, onChanged = onChanged, asks = asks)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            when (item) {
                is SliderItem -> CatalogSliderRow(item, onChanged)
                is ToggleItem -> CatalogToggleRow(item, onChanged)
                is ChoiceItem -> CatalogChoiceRow(item, onChanged)
                is NestedScreenItem -> CatalogRow(item.title, item.subtitle, null, iconFile = item.iconFile, progress = item.progress, look = item.progressLook, statusMark = item.statusMark) {
                    if (item.registryId == "social" && onOpenSocial != null) onOpenSocial() else nested = item.resolve()
                }
                is ActionItem, is AsyncActionItem -> CatalogActionRow(item, onChanged, asks(item))
                is NoticeItem -> CompanionNotice(item, onChanged, onOpen = { nested = it })
                // A page's header and bar (docs/SPEC.md 7k, "Page anatomy"): the shared pieces, touch only.
                is dev.droidtop.library.settings.PageHeaderItem -> dev.droidtop.ui.shared.PageHeaderView(item.header)
                is dev.droidtop.library.settings.ButtonBarItem -> CompanionButtonBar(item, onChanged)
                is TextBlockItem -> Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Unit
            }
        }
    }
}

/**
 * A nested catalog screen (Updates, the Android settings index, a plugin's panel) opened in place, with a way back.
 * [lead] goes under the title (a plugin panel's abilities line).
 */
@Composable
internal fun CompanionNestedScreen(
    screen: CatalogScreen,
    onBack: (() -> Unit)?,
    onChanged: () -> Unit,
    asks: (CatalogItem) -> Boolean = { it.confirmText != null },
    lead: String? = null,
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val items by produceState<List<CatalogItem>?>(null, screen, version) {
        value = withContext(Dispatchers.IO) { runCatching { screen.groups(context).flatMap { it.items } }.getOrDefault(emptyList()) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) CompanionPill("Back", onClick = onBack)
            // The one panel frame (tracker#515): a plugin panel's icon, then its name at the shell's title size.
            screen.iconFile?.let {
                AsyncImage(model = java.io.File(it), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(28.dp))
            }
            Text(
                screen.title,
                style = if (screen.panel) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        lead?.let { CompanionNote(it) }
        val list = items
        CompositionLocalProvider(LocalOneLineSubtitles provides screen.panel) {
            if (list == null) CompanionNote("Reading…") else CompanionCatalogItems(list, onChanged = { version++; onChanged() }, asks = asks)
        }
    }
}

@Composable
private fun CatalogRow(
    title: String,
    subtitle: String?,
    value: String?,
    // The value as a state chip (ChoiceItem.toned), in its tone or neutral (Droidtop/tracker#519).
    valueChip: Boolean = false,
    valueTone: NoticeTone? = null,
    // A picture before the title (CatalogItem.iconFile: a plugin's own icon, a store's mark; tracker#513).
    iconFile: String? = null,
    // A job's bar under the title and how it moves (CatalogItem.progress and progressLook, Droidtop/tracker#643); a
    // finished job's row has no bar and draws its check or cross. A tap opens the job's page, where its figures are.
    progress: Float? = null,
    look: dev.droidtop.library.settings.JobLook? = null,
    // One status glyph beside the title (CatalogItem.statusMark): a plugin that needs you or cannot run.
    statusMark: dev.droidtop.library.settings.StatusMark? = null,
    // An on/off state (ActionItem.state), drawn as the switch a toggle row draws, not said (Droidtop/tracker#738).
    switchOn: Boolean? = null,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (iconFile != null) {
            AsyncImage(
                model = java.io.File(iconFile),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(end = 12.dp).size(32.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f, fill = false))
                if (progress == null && look != null) dev.droidtop.ui.shared.JobMark(look, companionJobColors(), Modifier.padding(start = 8.dp))
                if (statusMark != null) dev.droidtop.ui.shared.StatusGlyph(statusMark, MaterialTheme.colorScheme.primary, Modifier.padding(start = 8.dp))
            }
            if (progress != null) {
                dev.droidtop.ui.shared.JobProgress(
                    progress,
                    look ?: dev.droidtop.library.settings.JobLook.of(progress)!!,
                    companionJobColors(),
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    height = 6.dp,
                )
            }
            if (!subtitle.isNullOrBlank()) RowSubtitle(subtitle)
        }
        if (!value.isNullOrBlank()) {
            if (valueChip) {
                val palette = LocalNoticePalette.current
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    color = valueTone?.let(palette::on) ?: colors.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .background(valueTone?.let(palette::plate) ?: colors.surfaceVariant, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            } else {
                // One value colour with the shell's rows (tracker#515): muted, not the accent, which is for what acts.
                Text(value, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (switchOn != null) Switch(checked = switchOn, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** True inside a plugin's panel ([CatalogScreen.panel]): each row's subtitle is one line until it is tapped. */
private val LocalOneLineSubtitles = staticCompositionLocalOf { false }

/**
 * A row's explanation. In a plugin's panel it is one line, as on the shell (tracker#515), and a tap shows it whole,
 * the companion's Info; elsewhere it is shown whole.
 */
@Composable
private fun RowSubtitle(text: String) {
    val oneLine = LocalOneLineSubtitles.current
    var whole by remember(text) { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (oneLine && !whole) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        modifier = if (oneLine) Modifier.clickable(onClickLabel = "Read all") { whole = !whole } else Modifier,
    )
}

/** A slider with a step button on each side: the step is the Quick Menu's own ([QuickTiles.sliderStep]). */
/** A catalog slider: the one slider row, written off the main thread, the catalog re-read after each write lands. */
@Composable
private fun CatalogSliderRow(item: SliderItem, onChanged: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    CompanionSliderRow(
        title = item.title,
        subtitle = item.subtitle,
        value = item.current.toFloat(),
        range = item.min.toFloat()..item.max.toFloat(),
        step = QuickTiles.sliderStep(item).toFloat(),
        writeKey = "catalog:${item.id}",
        write = { next ->
            item.onChange(app, kotlin.math.round(next).toInt())
            withContext(Dispatchers.Main) { onChanged() }
        },
        label = { catalogSliderLabel(it, item.min, item.max) },
    )
}

@Composable
private fun CatalogToggleRow(item: ToggleItem, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember(item) { mutableStateOf(item.current) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = on, role = Role.Switch) { next ->
                on = next
                scope.launch { item.onToggle(context, next); onChanged() }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!item.subtitle.isNullOrBlank()) RowSubtitle(item.subtitle!!)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** A choice: the row shows what it is set to, and a tap lays the options out under it. */
@Composable
private fun CatalogChoiceRow(item: ChoiceItem, onChanged: () -> Unit) {
    val context = LocalContext.current
    var open by remember(item) { mutableStateOf(false) }
    CatalogRow(item.title, item.subtitle, item.currentLabel(), valueChip = item.toned, valueTone = item.currentTone()) { open = !open }
    if (open) {
        Column(modifier = Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.options.forEach { option ->
                CompanionPill(option.label, selected = option.value == item.current) {
                    item.onSelect(context, option.value)
                    open = false
                    onChanged()
                }
            }
        }
    }
}

/**
 * An action: a tap runs it (an async one off the main thread, with its status in the value column). One that asks
 * first ([asksFirst]: its own question by default, or the rule the caller passes, such as `GameControls` for a plugin's
 * rows) shows its question with the safe answer first, the two well apart.
 */
@Composable
private fun CatalogActionRow(item: CatalogItem, onChanged: () -> Unit, asksFirst: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keyed by the row's id, not the item: finishing an action re-reads the screen (onChanged), which builds new item
    // objects, and a status kept per object vanished the moment the result arrived, so a tap on Check now showed
    // nothing at all (rig, build 1809). The gamepad's Settings keeps statusById the same way.
    var status by remember(item.id) { mutableStateOf<String?>(null) }
    var asking by remember(item.id) { mutableStateOf(false) }
    val state = (item as? ActionItem)?.state
    val value = status ?: item.value
    fun run() {
        asking = false
        when (item) {
            is ActionItem -> { item.run(context); onChanged() }
            is AsyncActionItem -> scope.launch {
                status = "Working…"
                status = withContext(Dispatchers.IO) { item.run(context) { line -> status = line } }.ifBlank { null }
                onChanged()
            }
            else -> Unit
        }
    }
    CatalogRow(item.title, item.subtitle, value, progress = item.progress, look = item.progressLook, statusMark = item.statusMark, switchOn = state) { if (asksFirst) asking = true else run() }
    if (asking) {
        Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            Text(item.confirmText ?: "${item.title}?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(top = 6.dp)) {
                CompanionPill("Cancel", selected = true) { asking = false }
                CompanionPill(item.title) { run() }
            }
        }
    }
}

/**
 * A notice on the companion (docs/SPEC.md 7k, "Notices"): the shared card, its action a tap, and its Info
 * opened in place under it (the details, which a long press of the text can copy) rather than in a sheet,
 * as the companion's rows open in place.
 */
@Composable
private fun CompanionNotice(item: NoticeItem, onChanged: () -> Unit, onOpen: (CatalogScreen) -> Unit) {
    val context = LocalContext.current
    var info by remember(item.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NoticeCard(
            notice = item.notice,
            placement = item.placement,
            onAction = item.notice.action?.let { action ->
                { item.opens?.let(onOpen) ?: scope.launch { action.run(context); onChanged() } }
            },
            onInfo = { info = !info },
        )
        val details = item.notice.details
        if (info && !details.isNullOrBlank()) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

/** A page's button bar on the companion: a tap presses; a button that asks first asks in place; More lists the rest. */
@Composable
private fun CompanionButtonBar(bar: dev.droidtop.library.settings.ButtonBarItem, onChanged: () -> Unit) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<dev.droidtop.library.settings.PageButton?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    fun press(button: dev.droidtop.library.settings.PageButton) {
        if (!button.enabled) return
        if (button.confirmTitle != null) {
            asking = button
            return
        }
        button.run(context)
        onChanged()
    }
    dev.droidtop.ui.shared.ButtonBarView(bar, selected = null, onPress = { slot ->
        val button = bar.slots.getOrNull(slot)
        if (button != null) press(button) else moreOpen = !moreOpen
    })
    if (moreOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            bar.more.forEach { button ->
                CatalogRow(button.shownLabel, null, null) {
                    moreOpen = false
                    press(button)
                }
            }
        }
    }
    asking?.let { button ->
        // The safe answer first (docs/SPEC.md 7k, copy rule 9).
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(button.confirmTitle.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            CatalogRow("Cancel", null, null) { asking = null }
            CatalogRow(button.label, null, null) {
                asking = null
                button.run(context)
                onChanged()
            }
        }
    }
}

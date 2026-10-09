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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.settings.confirmText
import dev.droidtop.shell.gamepad.QuickSection
import dev.droidtop.shell.gamepad.QuickTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
): List<SystemCard> = listOf(
    SystemCard("system", "System", section(QuickSection.SYSTEM), folded = false),
    SystemCard("display", "Display", section(QuickSection.DISPLAY), folded = true),
    SystemCard("sound", "Sound", section(QuickSection.AUDIO), folded = true),
    SystemCard("power", "Power", listOfNotNull(performanceMode), folded = true),
    SystemCard("storage", "Storage", emptyList(), folded = true, storage = true),
    SystemCard("privacy", "Privacy", listOfNotNull(privacy), folded = true),
).filter { it.storage || it.items.isNotEmpty() }

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
                is NestedScreenItem -> CatalogRow(item.title, item.subtitle, null) {
                    if (item.registryId == "social" && onOpenSocial != null) onOpenSocial() else nested = item.resolve()
                }
                is ActionItem, is AsyncActionItem -> CatalogActionRow(item, onChanged, asks(item))
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
            Text(screen.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        lead?.let { CompanionNote(it) }
        val list = items
        if (list == null) CompanionNote("Reading…") else CompanionCatalogItems(list, onChanged = { version++; onChanged() }, asks = asks)
    }
}

@Composable
private fun CatalogRow(title: String, subtitle: String?, value: String?, onClick: (() -> Unit)?) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (!value.isNullOrBlank()) Text(value, style = MaterialTheme.typography.labelLarge, color = colors.primary, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A slider with a step button on each side: the step is the Quick Menu's own ([QuickTiles.sliderStep]). */
@Composable
private fun CatalogSliderRow(item: SliderItem, onChanged: () -> Unit) {
    val context = LocalContext.current
    var value by remember(item) { mutableFloatStateOf(item.current.toFloat()) }
    val step = QuickTiles.sliderStep(item)
    fun set(next: Int) {
        val clamped = next.coerceIn(item.min, item.max)
        value = clamped.toFloat()
        item.onChange(context, clamped)
    }
    val percent = if (item.max > item.min) ((value - item.min) * 100 / (item.max - item.min)).toInt() else 0
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (!item.subtitle.isNullOrBlank()) CompanionNote(item.subtitle!!)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
            StepButton("−", "Lower ${item.title.lowercase()}") { set(value.toInt() - step); onChanged() }
            Slider(
                value = value,
                onValueChange = { set(it.toInt()) },
                onValueChangeFinished = onChanged,
                valueRange = item.min.toFloat()..item.max.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = item.title; stateDescription = "$percent%" },
            )
            StepButton("+", "Raise ${item.title.lowercase()}") { set(value.toInt() + step); onChanged() }
        }
    }
}

@Composable
private fun StepButton(label: String, spoken: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
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
            if (!item.subtitle.isNullOrBlank()) CompanionNote(item.subtitle!!)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** A choice: the row shows what it is set to, and a tap lays the options out under it. */
@Composable
private fun CatalogChoiceRow(item: ChoiceItem, onChanged: () -> Unit) {
    val context = LocalContext.current
    var open by remember(item) { mutableStateOf(false) }
    CatalogRow(item.title, item.subtitle, item.currentLabel()) { open = !open }
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
    var status by remember(item) { mutableStateOf<String?>(null) }
    var asking by remember(item) { mutableStateOf(false) }
    val state = (item as? ActionItem)?.state
    val value = status ?: item.value ?: state?.let { if (it) "On" else "Off" }
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
    CatalogRow(item.title, item.subtitle, value) { if (asksFirst) asking = true else run() }
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

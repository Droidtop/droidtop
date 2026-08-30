package dev.droidtop.shell.gamepad

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.theme.EsDeControllers
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.launch

/**
 * Real per-game metadata editor -- droidtop's own equivalent of real
 * ES-DE's `GuiMetaDataEd` (a real local clone kept at
 * /root/es-de-reference for ongoing reference: `es-app/src/guis/
 * GuiMetaDataEd.cpp`), covering the full real field set
 * `GameMetadataEntity`'s own doc comment cross-references against
 * `MetaData.cpp`. Loads existing values via
 * [Library.getMetadataForEditing] (real ES-DE default values when this
 * game has no row yet, matching `MetaDataList`'s own constructor
 * behavior), holds them as local editable state, and writes the whole
 * thing back atomically via [Library.saveMetadata] on "Save" -- same
 * real "edit the whole MetaDataList, commit once" shape `GuiMetaDataEd`
 * itself uses, not one network round-trip per field.
 *
 * `name`/`playCount`/`playtime`/`lastPlayed` are real ES-DE MetaDataDecl
 * fields but aren't editable here: `name` is droidtop's own real ROM
 * filename-derived [LibraryEntry.title], not a separately stored value;
 * the three statistics are real, already-tracked
 * [LibraryEntry.playCount]/`playtimeSeconds`/`lastPlayedEpochMs` (a
 * different real mechanism -- see `GameMetadataEntity`'s own doc
 * comment), not user-editable in real ES-DE's own editor either.
 * `launchScreen` is a plain nullable index here, not a live picker over
 * droidtop's own real display outputs -- `dev.droidtop.app.
 * DisplayOutputRepository` lives in `:app`, which this module can't
 * depend on (the one-way `:app` -> shells dependency graph, see
 * `OnboardingGate.kt`'s own doc comment) -- a real, separate follow-up
 * to surface real output names here, not attempted in this pass.
 */
@Composable
internal fun GameMetadataEditor(entry: LibraryEntry, library: Library, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<GameMetadataEntity?>(null) }
    var pickingController by remember { mutableStateOf(false) }

    LaunchedEffect(entry) {
        loaded = library.getMetadataForEditing(entry) ?: GameMetadataEntity(id = entry.id)
    }

    val current = loaded ?: run {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(48.dp)) {
            Text("Loading...", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    if (pickingController) {
        ControllerPicker(
            current = current.controllerShortName,
            onPick = { shortName ->
                loaded = current.copy(controllerShortName = shortName)
                pickingController = false
            },
            onDismiss = { pickingController = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.BACK) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        Text("Edit metadata", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(entry.title, color = Color.Gray, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { SectionLabel("Scraped fields") }
            item {
                MetadataTextRow("Description", current.description ?: "", multiline = true) {
                    loaded = current.copy(description = it.ifBlank { null })
                }
            }
            item {
                MetadataTextRow("Developer", current.developer ?: "") { loaded = current.copy(developer = it.ifBlank { null }) }
            }
            item {
                MetadataTextRow("Publisher", current.publisher ?: "") { loaded = current.copy(publisher = it.ifBlank { null }) }
            }
            item {
                MetadataTextRow("Genre", current.genre ?: "") { loaded = current.copy(genre = it.ifBlank { null }) }
            }
            item {
                MetadataTextRow("Players", current.players ?: "") { loaded = current.copy(players = it.ifBlank { null }) }
            }
            item {
                // Real ES-DE MD_DATE raw format: "YYYYMMDDT000000".
                MetadataTextRow("Release date (YYYYMMDDT000000)", current.releaseDate ?: "") {
                    loaded = current.copy(releaseDate = it.ifBlank { null })
                }
            }
            item {
                // Real ES-DE MD_RATING convention: 0.0-1.0.
                MetadataTextRow("Rating (0.0-1.0)", current.rating?.toString() ?: "", keyboardType = KeyboardType.Decimal) {
                    loaded = current.copy(rating = it.toFloatOrNull()?.coerceIn(0f, 1f))
                }
            }

            item { SectionLabel("Badges") }
            item { MetadataToggleRow("Favorite", current.favorite) { loaded = current.copy(favorite = it) } }
            item { MetadataToggleRow("Completed", current.completed) { loaded = current.copy(completed = it) } }
            item { MetadataToggleRow("Kid game", current.kidGame) { loaded = current.copy(kidGame = it) } }
            item { MetadataToggleRow("Broken / not working", current.broken) { loaded = current.copy(broken = it) } }
            item {
                MetadataPickerRow("Controller", EsDeControllers.byShortName(current.controllerShortName).displayName) {
                    pickingController = true
                }
            }
            item {
                MetadataTextRow("Alternative emulator", current.altEmulator ?: "") {
                    loaded = current.copy(altEmulator = it.ifBlank { null })
                }
            }
            item {
                Text(
                    if (entry.manualUri != null) "Manual: found (${entry.manualUri})" else "Manual: none found",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item { SectionLabel("Library management") }
            item { MetadataToggleRow("Hidden", current.hidden) { loaded = current.copy(hidden = it) } }
            item { MetadataToggleRow("Exclude from game counter", current.noGameCount) { loaded = current.copy(noGameCount = it) } }
            item { MetadataToggleRow("Exclude from multi-scrape", current.noMultiScrape) { loaded = current.copy(noMultiScrape = it) } }
            item { MetadataToggleRow("Hide metadata fields", current.hideMetadata) { loaded = current.copy(hideMetadata = it) } }
            item {
                MetadataTextRow("Sort name", current.sortName ?: "") { loaded = current.copy(sortName = it.ifBlank { null }) }
            }
            item {
                MetadataTextRow("Custom collections sort name", current.collectionSortName ?: "") {
                    loaded = current.copy(collectionSortName = it.ifBlank { null })
                }
            }
        }

        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionChip("Save", highlighted = true, onClick = {
                scope.launch {
                    library.saveMetadata(entry, current)
                    onDismiss()
                }
            })
            ActionChip("Cancel", highlighted = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color(0xFF9A9A9A),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun MetadataTextRow(
    label: String,
    value: String,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = !multiline,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color.Gray,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MetadataToggleRow(label: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onToggle(!value) }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.A) {
                    onToggle(!value)
                    true
                } else {
                    false
                }
            }
            .background(if (focused) Color(0xFF2A2A2A) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Text(if (value) "On" else "Off", color = if (value) Color(0xFF7FE08A) else Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MetadataPickerRow(label: String, currentValueLabel: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.A) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .background(if (focused) Color(0xFF2A2A2A) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Text(currentValueLabel, color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ControllerPicker(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.BACK) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Controller", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                ControllerRow("(None)", current == null) { onPick(null) }
            }
            items(EsDeControllers.all) { controller ->
                ControllerRow(controller.displayName, controller.shortName == current) { onPick(controller.shortName) }
            }
        }
    }
}

@Composable
private fun ControllerRow(label: String, isCurrent: Boolean, onPick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label + if (isCurrent) " (current)" else "",
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onPick)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.A) {
                    onPick()
                    true
                } else {
                    false
                }
            }
            .background(if (focused) Color(0xFF2A2A2A) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

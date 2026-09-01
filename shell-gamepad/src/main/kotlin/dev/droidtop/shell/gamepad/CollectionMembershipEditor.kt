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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.CollectionEntity
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.launch

/**
 * Real per-game collection membership editor -- droidtop's own real UI
 * for the custom-collections data layer (`CollectionEntity`/
 * `CollectionMemberEntity`, see `RomDatabase.kt`'s own doc comments),
 * droidtop's equivalent of real ES-DE's `toggleGameInCollection` (no
 * dedicated real ES-DE GUI screen for this specifically -- it's normally
 * a context-menu toggle per collection there too, same real shape this
 * screen gives it). Lists every real custom collection with its current
 * membership state for [entry], toggle on tap/A, plus a real "create new
 * collection" row that reveals an inline name field and creates +
 * immediately joins the new collection in one real action.
 */
@Composable
internal fun CollectionMembershipEditor(entry: LibraryEntry, library: Library, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var collections by remember { mutableStateOf<List<CollectionEntity>>(emptyList()) }
    var membership by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var creatingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(reloadToken) {
        collections = library.getCollections()
        membership = collections.filter { library.isCollectionMember(it.id, entry) }.map { it.id }.toSet()
        loaded = true
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
        Text("Collections", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(entry.title, color = Color.Gray, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))

        if (!loaded) {
            Text("Loading...", color = Color.White, style = MaterialTheme.typography.titleMedium)
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(collections) { collection ->
                    val isMember = collection.id in membership
                    CollectionToggleRow(collection.name, isMember) {
                        scope.launch {
                            val newState = library.toggleCollectionMembership(collection.id, entry) ?: return@launch
                            membership = if (newState) membership + collection.id else membership - collection.id
                            CollectionsRefresh.bump()
                        }
                    }
                }
                item {
                    if (creatingNew) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("Collection name") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedLabelColor = Color.White,
                                    unfocusedLabelColor = Color.Gray,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ActionChip("Create", highlighted = true, onClick = {
                                    val name = newName.trim()
                                    if (name.isNotEmpty()) {
                                        scope.launch {
                                            val created = library.createCollection(name) ?: return@launch
                                            library.toggleCollectionMembership(created.id, entry)
                                            newName = ""
                                            creatingNew = false
                                            reloadToken++
                                            CollectionsRefresh.bump()
                                        }
                                    }
                                })
                                ActionChip("Cancel", highlighted = false, onClick = { creatingNew = false; newName = "" })
                            }
                        }
                    } else {
                        CollectionToggleRow("+ New collection", null) { creatingNew = true }
                    }
                }
            }
        }

        ActionChip("Back", highlighted = false, modifier = Modifier.padding(top = 16.dp), onClick = onDismiss)
    }
}

/** [isMember] null renders a plain action row (no on/off state) -- used for "+ New collection." */
@Composable
private fun CollectionToggleRow(label: String, isMember: Boolean?, onClick: () -> Unit) {
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
            .background(if (focused) MenuTokens.SurfaceSelected else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        if (isMember != null) {
            Text(if (isMember) "In collection" else "Not in collection", color = if (isMember) MenuTokens.Affirmative else Color.Gray, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

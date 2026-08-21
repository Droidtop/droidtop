package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ES_DE_CONSOLE_SYSTEMS
import dev.droidtop.library.consoles.SystemOverridePrefs
import java.io.File

/**
 * Explicit folder-to-system assignment -- the more robust design Daijishō
 * itself actually uses (its real PlatformEntity has an `itemsSyncTreeUriList`,
 * confirmed via its decompiled sources), letting a user override
 * [dev.droidtop.library.consoles.resolveSystem]'s name-based matching for
 * a folder that isn't named exactly the way ES-DE's data expects, or isn't
 * recognized at all. Structurally modeled on Daijishō's own real settings
 * pattern (a plain list of items, tap one to edit) -- not a copy of its
 * UI, since Daijishō's own screens are traditional View/RecyclerView-based
 * and droidtop's shells are Compose throughout.
 *
 * Deliberately minimal for a first real version: one flat list of every
 * immediate subfolder across all configured games roots, each showing its
 * currently resolved system and a tap-to-search-and-reassign picker. No
 * per-system Player editing yet (still only ever
 * [dev.droidtop.library.consoles.DefaultPlayers.retroArch]) -- that's the
 * next real gap, same as [dev.droidtop.library.consoles.ConsoleRomProvider]'s
 * own doc comment already says.
 */
class ConsoleSystemsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ConsoleSystemsScreen() }
    }
}

@Composable
private fun ConsoleSystemsScreen() {
    val context = LocalContext.current
    var pickerForFolder by remember { mutableStateOf<File?>(null) }
    var version by remember { mutableStateOf(0) }

    val folders = remember(version) {
        GamesRootPrefs.gamesRootPaths(context)
            .map(::File)
            .flatMap { root -> (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.toList() }
            .sortedBy { it.name.lowercase() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220))) {
        val pickingFor = pickerForFolder
        if (pickingFor != null) {
            SystemPicker(
                onPick = { system ->
                    SystemOverridePrefs.set(context, pickingFor.absolutePath, system?.id)
                    pickerForFolder = null
                    version++
                },
                onDismiss = { pickerForFolder = null },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("Console systems", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Each folder's system is guessed from its name. Tap one to " +
                        "assign a different system by hand.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                if (folders.isEmpty()) {
                    Text("No game folders configured yet.", color = Color.Gray)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders) { folder ->
                        val resolved = SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name)
                        FolderRow(
                            folderName = folder.name,
                            resolvedSystem = resolved,
                            onClick = { pickerForFolder = folder },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folderName: String, resolvedSystem: ConsoleSystemDef?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color(0xFF1A1A1A))
            .padding(16.dp),
    ) {
        Text(folderName, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            resolvedSystem?.displayName ?: "Unrecognized -- tap to assign a system",
            color = if (resolvedSystem != null) Color.LightGray else Color(0xFFCC8800),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SystemPicker(onPick: (ConsoleSystemDef?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) {
            ES_DE_CONSOLE_SYSTEMS
        } else {
            ES_DE_CONSOLE_SYSTEMS.filter {
                it.displayName.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Assign a system", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(Color(0xFF1A1A1A))
                .padding(12.dp),
        )
        TextButton(onClick = { onPick(null) }) { Text("Clear override (use automatic matching)") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered) { system ->
                Text(
                    "${system.displayName} (${system.id})",
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(system) }
                        .padding(vertical = 10.dp),
                )
            }
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
}

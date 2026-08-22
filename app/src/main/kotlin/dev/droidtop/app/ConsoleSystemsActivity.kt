package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
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
import dev.droidtop.library.consoles.CustomPlayerPrefs
import dev.droidtop.library.consoles.ES_DE_CONSOLE_SYSTEMS
import dev.droidtop.library.consoles.Player
import dev.droidtop.library.consoles.PlayerOverridePrefs
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.consoles.resolvePlayer
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
 * One flat list of every immediate subfolder across all configured games
 * roots, each showing its currently resolved system (tap to reassign) and
 * its currently resolved [dev.droidtop.library.consoles.Player.AmStart]
 * (tap to pick a different installed one, or add a custom one) -- real
 * per-system Player selection, backed by
 * [dev.droidtop.library.consoles.availablePlayers] (installed-only,
 * covering both [dev.droidtop.library.consoles.KnownPlayers]' real presets
 * and [dev.droidtop.library.consoles.CustomPlayerPrefs] entries) and
 * [dev.droidtop.library.consoles.PlayerOverridePrefs] (the user's explicit
 * choice, when set).
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
    var playerPickerForSystem by remember { mutableStateOf<ConsoleSystemDef?>(null) }
    var addCustomPlayerForSystem by remember { mutableStateOf<ConsoleSystemDef?>(null) }
    var version by remember { mutableStateOf(0) }

    val folders = remember(version) {
        GamesRootPrefs.gamesRootPaths(context)
            .map(::File)
            .flatMap { root -> (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.toList() }
            .sortedBy { it.name.lowercase() }
    }

    // Plain black, matching GamepadShell's own background -- this screen is
    // reached from inside Handheld (Settings tab -> Console systems), so a
    // different dark-navy background here read as a visual inconsistency,
    // not an intentional design choice.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val pickingFor = pickerForFolder
        val pickingPlayerFor = playerPickerForSystem
        val addingCustomFor = addCustomPlayerForSystem
        when {
            addingCustomFor != null -> AddCustomPlayerScreen(
                system = addingCustomFor,
                onSave = { name, pkg, args, kill ->
                    CustomPlayerPrefs.add(context, addingCustomFor.id, name, args, pkg, kill)
                    addCustomPlayerForSystem = null
                    version++
                },
                onCancel = { addCustomPlayerForSystem = null },
            )
            pickingPlayerFor != null -> PlayerPicker(
                system = pickingPlayerFor,
                onPick = { player ->
                    PlayerOverridePrefs.set(context, pickingPlayerFor.id, player?.id)
                    playerPickerForSystem = null
                    version++
                },
                onAddCustom = { addCustomPlayerForSystem = pickingPlayerFor },
                onDismiss = { playerPickerForSystem = null },
            )
            pickingFor != null -> SystemPicker(
                onPick = { system ->
                    SystemOverridePrefs.set(context, pickingFor.absolutePath, system?.id)
                    pickerForFolder = null
                    version++
                },
                onDismiss = { pickerForFolder = null },
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("Console systems", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Each folder's system is guessed from its name. Tap the system to " +
                        "assign a different one by hand, or tap the player to choose which " +
                        "installed emulator (or a custom one you add) actually runs it.",
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
                            resolvedPlayer = resolved?.let { resolvePlayer(context, it) },
                            onClickSystem = { pickerForFolder = folder },
                            onClickPlayer = { if (resolved != null) playerPickerForSystem = resolved },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folderName: String,
    resolvedSystem: ConsoleSystemDef?,
    resolvedPlayer: Player.AmStart?,
    onClickSystem: () -> Unit,
    onClickPlayer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClickSystem)) {
            Text(folderName, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                resolvedSystem?.displayName ?: "Unrecognized -- tap to assign a system",
                color = if (resolvedSystem != null) Color.LightGray else Color(0xFFCC8800),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (resolvedSystem != null) {
            Text(
                resolvedPlayer?.let { "Player: ${it.name}" } ?: "No installed player for this system -- tap to add one",
                color = if (resolvedPlayer != null) Color(0xFF8AB4FF) else Color(0xFFCC8800),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClickPlayer).padding(top = 6.dp),
            )
        }
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

/**
 * Every real, currently-installed [Player.AmStart] for [system] (see
 * [availablePlayers] -- custom players, [dev.droidtop.library.consoles.KnownPlayers]'
 * real presets pulled from Daijishō's own wiki, then RetroArch), same
 * "flat list, tap to pick" shape [SystemPicker] already uses. Empty state
 * (no real emulator installed for this system yet) offers "Add a player"
 * directly rather than just saying "nothing here."
 */
@Composable
private fun PlayerPicker(system: ConsoleSystemDef, onPick: (Player.AmStart?) -> Unit, onAddCustom: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val players = remember(system) { availablePlayers(context, system) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Player for ${system.displayName}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Only installed emulators are listed. Pick one to launch this system with it every time.",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        if (players.isEmpty()) {
            Text("No installed emulator can run ${system.displayName} yet.", color = Color(0xFFCC8800))
        } else {
            TextButton(onClick = { onPick(null) }) { Text("Clear override (use first installed)") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(players) { player ->
                    Text(
                        player.name,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(player) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
        TextButton(onClick = onAddCustom) { Text("+ Add a player") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
}

/**
 * droidtop's own version of Daijishō's real "Add a player" form (confirmed
 * via a live screenshot of that exact screen this session: name field,
 * multiline am-start-arguments field pre-filled with a real template shape,
 * "kill package processes" toggle) -- same fields, same real
 * `{file.path}`/`{file.uri}` placeholder convention
 * [dev.droidtop.library.consoles.AmStartCommandToIntentConverter] already
 * implements. The one addition: an explicit package-name field, since
 * [availablePlayers] needs it separately to check install status without
 * re-parsing the arguments template.
 */
@Composable
private fun AddCustomPlayerScreen(
    system: ConsoleSystemDef,
    onSave: (name: String, pkg: String, args: String, kill: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("-a android.intent.action.VIEW\n-n org.example.app/.MainActivity\n-d {file.uri}") }
    var kill by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Add a player for ${system.displayName}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text("Player name", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(Color(0xFF1A1A1A)).padding(12.dp),
        )
        Text("Package name (e.g. org.example.app)", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = pkg,
            onValueChange = { pkg = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(Color(0xFF1A1A1A)).padding(12.dp),
        )
        Text(
            "Player am start arguments -- use \"{file.path}\" and \"{file.uri}\" to specify the file to be played.",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        BasicTextField(
            value = args,
            onValueChange = { args = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(Color(0xFF1A1A1A)).padding(12.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = kill, onCheckedChange = { kill = it })
            Text("Kill package processes before am start", color = Color.White, modifier = Modifier.padding(top = 12.dp))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                onClick = { onSave(name.ifBlank { pkg }, pkg, args, kill) },
                enabled = name.isNotBlank() && pkg.isNotBlank() && args.isNotBlank(),
            ) { Text("Save") }
        }
    }
}
